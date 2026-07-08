package com.vayunmathur.photos.util

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vayunmathur.photos.data.Selection
import java.nio.FloatBuffer

/**
 * Auto-select the foreground subject with an on-device neural model —
 * **U²-Net portable** salient-object detection on ONNX Runtime (standalone, no
 * MediaPipe, no Google Play Services, F-Droid clean).
 *
 * Unlike the old DeepLabV3 (Pascal-VOC, 21 fixed classes — it could only find the
 * ~20 VOC categories and called everything else background), U²-Net predicts a
 * general per-pixel saliency map, so it selects arbitrary subjects. Pixels above
 * [FG_THRESHOLD] form the selection; the hard edge is feathered slightly so
 * cutouts aren't jagged. Runs off the main thread; [onResult] is posted to the
 * main thread (null on failure or if the model asset is missing).
 *
 * Model asset: [ASSET] `u2netp.onnx` (~4.6 MB, Apache-2.0; generate with
 * `scripts/photos/prepare_models.py`). Input NCHW `[1,3,`[SIZE]`,`[SIZE]`]` RGB,
 * ImageNet-normalised; primary output `[1,1,`[SIZE]`,`[SIZE]`]` saliency already
 * through a sigmoid.
 */
fun segmentSubject(context: Context, bitmap: Bitmap, onResult: (Selection?) -> Unit) {
    Thread {
        val sel = runCatching { runSegmenter(context, bitmap) }
            .getOrElse { Log.e("MlSegmentation", "segmentation failed", it); null }
        Handler(Looper.getMainLooper()).post { onResult(sel) }
    }.start()
}

private const val ASSET = "u2netp.onnx"
private const val SIZE = 320
private const val FG_THRESHOLD = 0.5f
// ImageNet normalisation (the rembg U²-Net preprocessing convention).
private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

private val ortEnv: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
private val segLock = Any()
@Volatile private var segSession: OrtSession? = null
@Volatile private var segInitTried = false

private fun ensureSession(context: Context): OrtSession? {
    segSession?.let { return it }
    synchronized(segLock) {
        segSession?.let { return it }
        if (segInitTried) return null
        segInitTried = true
        return try {
            val bytes = context.applicationContext.assets.open(ASSET).use { it.readBytes() }
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
            }
            ortEnv.createSession(bytes, opts).also { segSession = it }
        } catch (e: Throwable) {
            Log.e("MlSegmentation", "U\u00b2-Net segmenter unavailable — add $ASSET to photos assets (see prepare_models.py).", e)
            null
        }
    }
}

private fun runSegmenter(context: Context, bitmap: Bitmap): Selection? {
    val session = ensureSession(context) ?: return null

    // Cap the returned mask resolution for speed/memory; the model itself always
    // runs at its fixed [SIZE] input and we upsample the saliency map to this.
    val maxDim = 512
    val scale = minOf(1f, maxDim.toFloat() / maxOf(bitmap.width, bitmap.height))
    val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val h = (bitmap.height * scale).toInt().coerceAtLeast(1)

    // Preprocess: resize to SIZE×SIZE, RGB, /255 then ImageNet normalise, NCHW.
    val safe = if (bitmap.config == Bitmap.Config.HARDWARE || bitmap.config == null) {
        bitmap.copy(Bitmap.Config.ARGB_8888, false)
    } else bitmap
    val input = Bitmap.createScaledBitmap(safe, SIZE, SIZE, true)
    val px = IntArray(SIZE * SIZE)
    input.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
    if (input != safe) input.recycle()
    if (safe != bitmap) safe.recycle()

    val area = SIZE * SIZE
    val chw = FloatArray(3 * area)
    for (i in 0 until area) {
        val p = px[i]
        chw[i] = ((((p shr 16) and 0xFF) / 255f) - MEAN[0]) / STD[0]           // R
        chw[area + i] = ((((p shr 8) and 0xFF) / 255f) - MEAN[1]) / STD[1]     // G
        chw[2 * area + i] = (((p and 0xFF) / 255f) - MEAN[2]) / STD[2]         // B
    }

    val saliency: FloatArray = synchronized(segLock) {
        val inputName = session.inputNames.iterator().next()
        OnnxTensor.createTensor(
            ortEnv,
            FloatBuffer.wrap(chw),
            longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong()),
        ).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                // First output is the main saliency map [1,1,SIZE,SIZE] (sigmoid).
                val out = result.get(0) as OnnxTensor
                val vec = FloatArray(area)
                out.floatBuffer.get(vec)
                vec
            }
        }
    }

    // Normalise to 0..1 (U²-Net output isn't guaranteed to span the full range).
    var lo = Float.MAX_VALUE
    var hi = -Float.MAX_VALUE
    for (v in saliency) { if (v < lo) lo = v; if (v > hi) hi = v }
    val range = (hi - lo).takeIf { it > 1e-6f } ?: 1f

    // Upsample the SIZE×SIZE mask to the (w,h) selection grid (nearest is fine;
    // the edge gets feathered below) and threshold into a foreground mask.
    val mask = FloatArray(w * h)
    for (y in 0 until h) {
        val sy = (y * SIZE / h).coerceIn(0, SIZE - 1)
        for (x in 0 until w) {
            val sx = (x * SIZE / w).coerceIn(0, SIZE - 1)
            val norm = (saliency[sy * SIZE + sx] - lo) / range
            mask[y * w + x] = if (norm >= FG_THRESHOLD) 1f else 0f
        }
    }

    // Feather the hard edge slightly so cutouts aren't jagged.
    return Selection(mask, w, h).applyFeather(1.5f)
}
