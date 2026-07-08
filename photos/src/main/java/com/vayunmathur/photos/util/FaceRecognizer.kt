package com.vayunmathur.photos.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * On-device face detection + recognition. Everything runs locally with no Google
 * Play Services and no cloud (F-Droid clean).
 *
 * Pipeline:
 *  1. Detection — a **BlazeFace** ONNX model ([DETECTOR_ASSET]) run on ONNX
 *     Runtime finds faces and returns a bounding box + 6 landmarks per face
 *     (eyes, nose, mouth, cheeks). Anchor decoding and NMS are baked into the
 *     graph, so we just feed a 128x128 image (plus conf/iou/max-detection
 *     scalars) and read back the selected boxes. No MediaPipe / Play Services.
 *  2. Alignment — we take the two eye landmarks and compute a similarity
 *     transform (rotate + uniform scale + translate) that maps them onto a fixed
 *     canonical position inside a [INPUT_SIZE]x[INPUT_SIZE] crop. Aligning faces
 *     to a canonical pose is what makes same-person matching reliable.
 *  3. Embedding — a dedicated FACE-recognition model ([EMBEDDER_ASSET],
 *     **EdgeFace**) runs on the aligned crop via an ONNX Runtime [OrtSession]
 *     (`com.microsoft.onnxruntime`, MIT — no LiteRT/TensorFlow-Lite dependency
 *     is needed here). EdgeFace is an
 *     ArcFace-trained transformer-CNN hybrid; the shipped export is fp32
 *     (a few MB). Pixels are packed **NCHW** planar and
 *     normalised as (px - 127.5) / 127.5, the ArcFace convention.
 *  4. Matching — embeddings are L2-normalised and compared with cosine
 *     similarity to cluster faces of the same person (see [CLUSTER_THRESHOLD]).
 *
 * ## Required model assets (in `photos/src/main/assets/`)
 *
 *  - [DETECTOR_ASSET] — a **BlazeFace** face detector in ONNX format (short
 *    range, 128x128 input, decode+NMS in-graph). Sourced from
 *    `garavv/blazeface-onnx` (`blaze.onnx`); `prepare_models.py` downloads it to
 *    `photos/src/main/assets/blazeface.onnx`. Input NCHW `[1,3,128,128]` RGB in
 *    [0,1] (plain /255); output `[1,N,16]` per-face rows of normalised
 *    `[top_y, top_x, bot_y, bot_x, leftEyeX, leftEyeY, rightEyeX, rightEyeY,
 *    noseX, noseY, mouthX, mouthY, leftCheekX, leftCheekY, rightCheekX,
 *    rightCheekY]`.
 *
 *  - [EMBEDDER_ASSET] — an **EdgeFace** face-recognition embedder in ONNX
 *    format. Expected input: NCHW `[1,3,`[INPUT_SIZE]`,`[INPUT_SIZE]`]` RGB,
 *    normalised (px - 127.5) / 127.5; output: a 512-d float embedding (the
 *    dimension is read from the model at run time). The code L2-normalises the
 *    output itself.
 *
    Generate it with `scripts/photos/prepare_models.py`, which fetches the
 *    EdgeFace weights and exports to **fp32** ONNX, writing
 *    `photos/src/main/assets/edgeface.onnx`. (It is intentionally not INT8
 *    quantized: dynamic quant emits `ConvInteger`, which the ONNX Runtime
 *    Android package cannot run.) NOTE: EdgeFace ships under a
 *    **non-commercial** research licence — this project uses it deliberately;
 *    swap the asset if you need a permissive licence. If the input size differs
 *    from 112, update [INPUT_SIZE]; if the normalisation differs, update [embed].
 *    When you change the model, bump [EMBEDDER_VERSION] so existing clusters are
 *    rebuilt with the new embeddings.
 *
 * If either model is missing the feature stays inert (no crash): see
 * [modelsAvailable], which the UI checks before enabling.
 */
object FaceRecognizer {
    const val DETECTOR_ASSET = "blazeface.onnx"
    const val EMBEDDER_ASSET = "edgeface.onnx"

    /** Square input side (px) the BlazeFace detector expects. */
    private const val DETECT_SIZE = 128

    /** Detector thresholds (fed as graph inputs; decode + NMS run in-graph). */
    private const val DETECT_CONF = 0.5f
    private const val DETECT_IOU = 0.3f
    private const val DETECT_MAX = 25L

    /** Square input side (px) the embedder expects. EdgeFace/ArcFace is 112. */
    const val INPUT_SIZE = 112

    /**
     * Bump this whenever the embedder model (or preprocessing) changes. The face
     * worker compares it against a stored value and, on mismatch, clears existing
     * clusters and re-scans so photos are re-grouped with the new embeddings.
     * (v1 = old MobileNetV3 general embedder; v2 = MobileFaceNet + alignment;
     * v3 = EdgeFace INT8 on ONNX Runtime; v4 = EdgeFace fp32 — INT8 dynamic
     * quant emits ConvInteger, which ORT-Android can't run; v5 = BlazeFace ONNX
     * detector replacing MediaPipe, which shifts alignment/embeddings slightly.)
     */
    const val EMBEDDER_VERSION = 5

    /**
     * Minimum cosine similarity for a face to join an existing cluster instead of
     * starting a new one. Single tunable knob. EdgeFace is ArcFace-trained, so
     * its cosine distribution is more spread out than MobileFaceNet's ~0.4–0.6:
     * same-person pairs sit higher and impostors lower, so a stricter floor is
     * appropriate. 0.55 is the starting point — validate on sample faces and
     * adjust: higher = stricter/more clusters, lower = looser/fewer clusters.
     */
    const val CLUSTER_THRESHOLD = 0.55f

    /**
     * Cosine similarity above which two whole clusters are merged in the
     * second-pass cleanup (reduces duplicate person-groups). Kept a bit stricter
     * than [CLUSTER_THRESHOLD] because it compares averaged centroids.
     */
    const val MERGE_THRESHOLD = 0.65f

    private const val TAG = "FaceRecognizer"

    // Canonical eye positions inside the aligned crop, as fractions of the side.
    // Derived from the standard ArcFace 5-point template at 112px
    // (left eye 38.29, right eye 73.53, both at y≈51.7).
    private const val LEFT_EYE_X = 38.2946f / 112f
    private const val RIGHT_EYE_X = 73.5318f / 112f
    private const val EYE_Y = 51.6963f / 112f

    /**
     * One detected face: its L2-normalised embedding plus the detector's bounding
     * box, normalised to 0..1 of the source image so it survives resizing (used
     * for the representative thumbnail crop in the UI).
     */
    data class DetectedFace(
        val embedding: FloatArray,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val lock = Any()

    @Volatile private var detector: OrtSession? = null
    @Volatile private var embedder: OrtSession? = null
    @Volatile private var initFailed = false

    /** True only if both model assets are present in the APK. */
    fun modelsAvailable(context: Context): Boolean =
        assetExists(context, DETECTOR_ASSET) && assetExists(context, EMBEDDER_ASSET)

    private fun assetExists(context: Context, name: String): Boolean = try {
        context.assets.open(name).close()
        true
    } catch (_: Exception) {
        false
    }

    @Synchronized
    private fun ensureInit(context: Context): Boolean {
        if (detector != null && embedder != null) return true
        if (initFailed) return false
        return try {
            val app = context.applicationContext
            val opts = OrtSession.SessionOptions().apply {
                // Single-threaded keeps sustained CPU/battery use low.
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            val detBytes = readAsset(app, DETECTOR_ASSET)
                ?: throw IllegalStateException("Missing $DETECTOR_ASSET")
            detector = env.createSession(detBytes, opts)
            val modelBytes = readAsset(app, EMBEDDER_ASSET)
                ?: throw IllegalStateException("Missing $EMBEDDER_ASSET")
            embedder = env.createSession(modelBytes, opts)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Face models unavailable — drop a BlazeFace $DETECTOR_ASSET and an EdgeFace $EMBEDDER_ASSET into photos assets (see FaceRecognizer docs).", e)
            try { detector?.close() } catch (_: Exception) {}
            detector = null
            try { embedder?.close() } catch (_: Exception) {}
            embedder = null
            initFailed = true
            false
        }
    }

    private fun readAsset(context: Context, name: String): ByteArray? = try {
        context.assets.open(name).use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    /** Detect every face in [bitmap] and return one [DetectedFace] per face. */
    fun detectAndEmbed(context: Context, bitmap: Bitmap): List<DetectedFace> {
        if (!ensureInit(context)) return emptyList()
        val det = detector ?: return emptyList()
        val emb = embedder ?: return emptyList()

        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false)

        val detections = try {
            runDetector(det, argb)
        } catch (e: Exception) {
            Log.e(TAG, "Face detection failed", e)
            return emptyList()
        }

        val out = ArrayList<DetectedFace>(detections.size)
        for (d in detections) {
            val rect = faceRect(d, argb.width, argb.height) ?: continue
            val aligned = alignFace(argb, d, rect) ?: continue
            try {
                val embedding = l2Normalize(embed(emb, aligned))
                out += DetectedFace(
                    embedding = embedding,
                    left = rect.left.toFloat() / argb.width,
                    top = rect.top.toFloat() / argb.height,
                    right = rect.right.toFloat() / argb.width,
                    bottom = rect.bottom.toFloat() / argb.height,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Face embedding failed", e)
            } finally {
                aligned.recycle()
            }
        }
        return out
    }

    /** One raw BlazeFace detection: bbox + eye landmarks, all normalised 0..1. */
    private class RawDetection(
        val top: Float,
        val left: Float,
        val bottom: Float,
        val right: Float,
        val leftEyeX: Float,
        val leftEyeY: Float,
        val rightEyeX: Float,
        val rightEyeY: Float,
    )

    /**
     * Run BlazeFace on [bmp] (plain-resized to [DETECT_SIZE]²) and return the
     * selected detections. Anchor decode + NMS run in-graph; the output is
     * `[1,N,16]` of normalised `[top_y, top_x, bot_y, bot_x, leftEyeX, leftEyeY,
     * rightEyeX, rightEyeY, ...]` — only the first 8 columns are used here.
     * Coords are normalised to the (stretched) input, which maps directly back to
     * the source image since the resize is a full stretch.
     */
    private fun runDetector(session: OrtSession, bmp: Bitmap): List<RawDetection> {
        val scaled = Bitmap.createScaledBitmap(bmp, DETECT_SIZE, DETECT_SIZE, true)
        val px = IntArray(DETECT_SIZE * DETECT_SIZE)
        scaled.getPixels(px, 0, DETECT_SIZE, 0, 0, DETECT_SIZE, DETECT_SIZE)
        if (scaled != bmp) scaled.recycle()

        val area = DETECT_SIZE * DETECT_SIZE
        val input = FloatArray(3 * area)
        for (i in 0 until area) {
            val p = px[i]
            input[i] = ((p shr 16) and 0xFF) / 255f            // R plane
            input[area + i] = ((p shr 8) and 0xFF) / 255f       // G plane
            input[2 * area + i] = (p and 0xFF) / 255f           // B plane
        }

        synchronized(lock) {
            val tensors = HashMap<String, OnnxTensor>()
            try {
                tensors["image"] = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(input),
                    longArrayOf(1, 3, DETECT_SIZE.toLong(), DETECT_SIZE.toLong()),
                )
                tensors["conf_threshold"] = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(floatArrayOf(DETECT_CONF)), longArrayOf(1),
                )
                tensors["max_detections"] = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(longArrayOf(DETECT_MAX)), longArrayOf(1),
                )
                tensors["iou_threshold"] = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(floatArrayOf(DETECT_IOU)), longArrayOf(1),
                )
                session.run(tensors).use { result ->
                    val outTensor = result.get(0) as OnnxTensor
                    val shape = outTensor.info.shape // [1, N, 16]
                    val n = if (shape.size >= 2) shape[1].toInt() else 0
                    if (n <= 0) return emptyList()
                    val buf = FloatArray(n * 16)
                    outTensor.floatBuffer.get(buf)
                    val list = ArrayList<RawDetection>(n)
                    for (k in 0 until n) {
                        val o = k * 16
                        // Skip zero-padded / degenerate rows.
                        if (buf[o + 2] - buf[o] <= 0f || buf[o + 3] - buf[o + 1] <= 0f) continue
                        list += RawDetection(
                            top = buf[o], left = buf[o + 1], bottom = buf[o + 2], right = buf[o + 3],
                            leftEyeX = buf[o + 4], leftEyeY = buf[o + 5],
                            rightEyeX = buf[o + 6], rightEyeY = buf[o + 7],
                        )
                    }
                    return list
                }
            } finally {
                tensors.values.forEach { it.close() }
            }
        }
    }

    /**
     * Warp the source image so the two eye landmarks land on fixed canonical
     * positions in an [INPUT_SIZE] crop (a similarity transform: rotation +
     * uniform scale + translation). Falls back to a plain resized box crop when
     * eye landmarks are unavailable.
     */
    private fun alignFace(src: Bitmap, d: RawDetection, box: Rect): Bitmap? {
        val hasEyes = d.leftEyeX != 0f || d.leftEyeY != 0f || d.rightEyeX != 0f || d.rightEyeY != 0f
        if (hasEyes) {
            // Sort the two eyes by x so the left-most maps to the left canonical
            // eye — keeps the face upright and never mirrored.
            val eyes = listOf(
                floatArrayOf(d.leftEyeX * src.width, d.leftEyeY * src.height),
                floatArrayOf(d.rightEyeX * src.width, d.rightEyeY * src.height),
            ).sortedBy { it[0] }
            val srcPts = floatArrayOf(eyes[0][0], eyes[0][1], eyes[1][0], eyes[1][1])
            val dstPts = floatArrayOf(
                LEFT_EYE_X * INPUT_SIZE, EYE_Y * INPUT_SIZE,
                RIGHT_EYE_X * INPUT_SIZE, EYE_Y * INPUT_SIZE,
            )
            val matrix = Matrix()
            if (matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 2)) {
                return try {
                    val outBmp = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
                    Canvas(outBmp).drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                    outBmp
                } catch (_: Exception) {
                    null
                }
            }
        }
        // Fallback: no usable landmarks — scale the axis-aligned box to the input.
        return try {
            val crop = Bitmap.createBitmap(src, box.left, box.top, box.width(), box.height())
            val scaled = Bitmap.createScaledBitmap(crop, INPUT_SIZE, INPUT_SIZE, true)
            if (scaled != crop) crop.recycle()
            scaled
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Run the embedder on an [INPUT_SIZE] aligned crop, returning the raw vector.
     * EdgeFace/ArcFace expects an NCHW planar `[1,3,H,W]` float tensor with pixels
     * normalised (px - 127.5) / 127.5.
     */
    private fun embed(session: OrtSession, face: Bitmap): FloatArray {
        val area = INPUT_SIZE * INPUT_SIZE
        val pixels = IntArray(area)
        face.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        val input = FloatArray(3 * area)
        for (i in 0 until area) {
            val p = pixels[i]
            input[i] = (((p shr 16) and 0xFF) - 127.5f) / 127.5f          // R plane
            input[area + i] = (((p shr 8) and 0xFF) - 127.5f) / 127.5f     // G plane
            input[2 * area + i] = ((p and 0xFF) - 127.5f) / 127.5f         // B plane
        }
        synchronized(lock) {
            val inputName = session.inputNames.iterator().next()
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(input),
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
            ).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val out = result.get(0) as OnnxTensor
                    val vec = FloatArray(out.info.shape.last().toInt())
                    out.floatBuffer.get(vec)
                    return vec
                }
            }
        }
    }

    /** Expand the normalised detector box by a small margin and clamp to pixels. */
    private fun faceRect(d: RawDetection, w: Int, h: Int): Rect? {
        val bw = (d.right - d.left) * w
        val bh = (d.bottom - d.top) * h
        val marginX = bw * 0.15f
        val marginY = bh * 0.15f
        val left = (d.left * w - marginX).toInt().coerceIn(0, w - 1)
        val top = (d.top * h - marginY).toInt().coerceIn(0, h - 1)
        val right = (d.right * w + marginX).toInt().coerceIn(left + 1, w)
        val bottom = (d.bottom * h + marginY).toInt().coerceIn(top + 1, h)
        if (right - left < 8 || bottom - top < 8) return null
        return Rect(left, top, right, bottom)
    }

    /** Cosine similarity of two embeddings (range roughly -1..1). */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
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
