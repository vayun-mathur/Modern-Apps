package com.vayunmathur.photos.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ml.ClipHandle
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device **TinyCLIP-ViT-8M/16 Text-3M** semantic embedder for photo search.
 *
 * Photos runs its own embedder again. It previously delegated to the OpenAssistant app over IPC
 * for SigLIP2 vectors, which meant semantic search only worked if a second app was installed and
 * had finished a ~452 MB model download. The model now ships in the APK
 * (`assets/clip/tinyclip.maml`, 22.6 MiB int8) so search works on a clean install with no network.
 *
 * # It runs on `:library:ml`, not onnxruntime
 *
 * The two towers are hardcoded forward passes in `library/ml/src/main/rust/src/nets/tinyclip.rs`,
 * executed by our own Vulkan compute runtime through [ClipHandle]. That removed the last
 * `onnxruntime-reduced-android` dependency from this APK - 10,466,856 bytes of arm64 `.so`, against
 * the 797 KB `libmodelrunner.so` that was already here for face detection, segmentation and OCR.
 *
 * It is also *more* accurate than the ONNX path it replaces. Cosine against the fp32 export is
 * 0.999864 for an image and 0.999394 for a query, against the int8 export's 0.998478 and 0.982881:
 * that export quantised the 49,408-row token table per *tensor* with a zero point, and this one
 * quantises per row. `photos/src/main/assets/clip/README.md` has the measurements.
 *
 * The two paths agree with each other only to 0.9983 (image) and 0.9835 (text), which is why
 * [EMBEDDER_VERSION] is 4 and not 3 — a library indexed by the old engine and queried through this
 * one would rank noticeably worse than one indexed by either alone.
 *
 * # Two towers, one handle
 *
 * Unlike the ONNX export, which was a single combined graph needing a dummy input for whichever
 * side it was not reading, the two towers are separate passes over one file. An image call does no
 * text work and a query does no vision work.
 *
 * Vectors are L2-normalised, stored on the [com.vayunmathur.photos.data.Photo] row by
 * [com.vayunmathur.photos.work.SyncWorker.runClipIndexing], and compared with [cosine] at query
 * time. The stored BLOB format is unchanged from the ONNX engine: 512 little-endian floats.
 */
object ClipEmbedder {

    /** HuggingFace source, also the id stored for change-detection re-indexing. */
    const val MODEL_ID = "onnx-community/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M-ONNX"

    /**
     * Bump whenever the embedding space changes so
     * [com.vayunmathur.photos.work.SyncWorker.runClipIndexing] clears every stored vector and
     * re-indexes.
     *
     * 4 = TinyCLIP's 512-d space on `:library:ml`. 3 was the same model on onnxruntime, and the two
     * agree only to 0.9835 on a text query — see the class docs. 2 was OpenAssistant's 768-d SigLIP2.
     */
    const val EMBEDDER_VERSION = 4

    /** Whether semantic search can run at all. */
    enum class Support { READY, UNAVAILABLE }

    /** Model identity + dimension behind the vectors, for change-detection re-index. */
    data class Info(val modelId: String, val dim: Int)

    /** One image could not be embedded, but the embedder itself is healthy. */
    class ImageFailedException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    private const val TAG = "ClipEmbedder"

    private const val RGB_CHANNELS = 3
    private const val CHANNEL_MAX_F = 255f
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CHANNEL_MASK = 0xFF

    /** Square RGB input side the vision tower expects (`preprocessor_config.json`). */
    private const val IMAGE_SIZE = ClipHandle.IMAGE_SIZE

    /** CLIP's channel normalisation, from `preprocessor_config.json`. */
    private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    private val lock = Any()

    @Volatile private var clip: ClipHandle? = null
    @Volatile private var tokenizer: ClipTokenizer? = null
    @Volatile private var initTried = false

    /** Fast capability probe. Loads the model on first call. */
    fun embeddingSupport(context: Context): Support =
        if (ensureInit(context)) Support.READY else Support.UNAVAILABLE

    fun embeddingInfo(context: Context): Info {
        ensureInit(context)
        return Info(MODEL_ID, ClipHandle.DIMENSION)
    }

    private fun ensureInit(context: Context): Boolean {
        clip?.let { return true }
        synchronized(lock) {
            clip?.let { return true }
            if (initTried) return false
            initTried = true
            val app = context.applicationContext
            val tok = ClipTokenizer.load(app)
            if (tok == null) {
                Log.error(TAG, "CLIP merges asset missing")
                return false
            }
            // Construction never throws: an absent, compressed or malformed asset, a missing
            // `libmodelrunner.so` and a device without fp16 compute all come back unavailable.
            // A linkage failure inside the native bring-up is an Error, so guard
            // it here to keep that guarantee on incomplete host/device libraries.
            val handle = try {
                ClipHandle.inAssets(app.assets)
            } catch (_: LinkageError) {
                Log.error(TAG, "TinyCLIP native library incomplete")
                return false
            } catch (_: RuntimeException) {
                Log.error(TAG, "TinyCLIP bring-up failed")
                return false
            }
            if (!handle.isAvailable) {
                Log.error(TAG, "cannot bring up $handle")
                handle.close()
                return false
            }
            clip = handle
            tokenizer = tok
            Log.status(TAG, "TinyCLIP embedder ready (dim=${ClipHandle.DIMENSION})")
            return true
        }
    }

    /**
     * Embed [uri] into an L2-normalised image vector.
     *
     * @throws ImageFailedException if this particular image cannot be decoded.
     * @throws IllegalStateException if the embedder is unavailable.
     */
    suspend fun imageEmbedding(context: Context, uri: Uri): FloatArray =
        withContext(Dispatchers.Default) {
            check(ensureInit(context)) { "TinyCLIP embedder unavailable" }
            val bitmap = decode(context, uri)
                ?: throw ImageFailedException("could not decode $uri")
            val pixels = try {
                preprocess(bitmap)
            } finally {
                bitmap.recycle()
            }
            // The handle re-records the network when the tower changes, so calls on it are
            // serialised for the same reason the ORT session's were.
            val raw = synchronized(lock) { clip?.imageEmbedding(pixels) }
                ?: throw ImageFailedException("the vision tower failed for $uri")
            l2Normalize(raw)
        }

    /** Embed [query] into an L2-normalised vector in the SAME space as [imageEmbedding]. */
    suspend fun textEmbedding(context: Context, query: String): FloatArray =
        withContext(Dispatchers.Default) {
            check(ensureInit(context)) { "TinyCLIP embedder unavailable" }
            val tok = tokenizer ?: error("tokenizer not loaded")
            // The tokenizer pads to 77; the tower is causal and pools at `<|endoftext|>`, so the
            // padding is dropped. That gives the identical vector for a quarter of the work on a
            // typical query, and it is what tells native which position to pool.
            val ids = trimToEnd(tok.tokenize(query))
            val raw = synchronized(lock) { clip?.textEmbedding(ids) }
                ?: error("the text tower failed")
            l2Normalize(raw)
        }

    /**
     * A tokenizer's fixed-length output cut to end at `<|endoftext|>`.
     *
     * [ClipTokenizer.tokenize] always appends the end token and then zero-pads to
     * [ClipTokenizer.CONTEXT_LENGTH], so the token is present and appears once. If it somehow is
     * not, the whole array is passed through rather than silently pooling position 0 — which would
     * be the `<|startoftext|>` embedding and carry no query at all.
     */
    private fun trimToEnd(ids: IntArray): IntArray {
        val end = ids.indexOf(ClipTokenizer.END_TOKEN)
        return if (end >= 0) ids.copyOfRange(0, end + 1) else ids
    }

    /** Decode [uri], downsampling during decode so a 48 MP photo never lands on the heap. */
    private fun decode(context: Context, uri: Uri): Bitmap? = try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var scale = 1
        while (bounds.outWidth / (scale * 2) >= IMAGE_SIZE &&
            bounds.outHeight / (scale * 2) >= IMAGE_SIZE
        ) {
            scale *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = scale
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (t: IOException) {
        Log.status(TAG, "decode failed for $uri", t)
        null
    } catch (t: SecurityException) {
        Log.status(TAG, "decode failed for $uri", t)
        null
    }

    /**
     * CLIP preprocessing: resize so the shortest edge is [IMAGE_SIZE], centre-crop to a square,
     * rescale to `[0,1]`, normalise by [MEAN]/[STD], pack NCHW (RGB).
     */
    private fun preprocess(src: Bitmap): FloatArray {
        val safe = if (src.config == Bitmap.Config.HARDWARE || src.config == null) {
            src.copy(Bitmap.Config.ARGB_8888, false) ?: src
        } else {
            src
        }

        // Shortest-edge resize preserving aspect ratio, then centre crop.
        val ratio = IMAGE_SIZE.toFloat() / minOf(safe.width, safe.height)
        val rw = (safe.width * ratio).roundToInt().coerceAtLeast(IMAGE_SIZE)
        val rh = (safe.height * ratio).roundToInt().coerceAtLeast(IMAGE_SIZE)
        val scaled = Bitmap.createScaledBitmap(safe, rw, rh, true)
        val left = (rw - IMAGE_SIZE) / 2
        val top = (rh - IMAGE_SIZE) / 2

        val px = IntArray(IMAGE_SIZE * IMAGE_SIZE)
        scaled.getPixels(px, 0, IMAGE_SIZE, left, top, IMAGE_SIZE, IMAGE_SIZE)
        if (scaled !== safe) scaled.recycle()
        if (safe !== src) safe.recycle()

        val area = IMAGE_SIZE * IMAGE_SIZE
        val out = FloatArray(RGB_CHANNELS * area)
        for (i in 0 until area) {
            val p = px[i]
            out[i] = ((((p shr RED_SHIFT) and CHANNEL_MASK) / CHANNEL_MAX_F) - MEAN[0]) / STD[0]
            out[area + i] = ((((p shr GREEN_SHIFT) and CHANNEL_MASK) / CHANNEL_MAX_F) - MEAN[1]) / STD[1]
            out[2 * area + i] = (((p and CHANNEL_MASK) / CHANNEL_MAX_F) - MEAN[2]) / STD[2]
        }
        return out
    }

    fun close() {
        synchronized(lock) { closeLocked() }
    }

    private fun closeLocked() {
        runCatching { clip?.close() }
        clip = null
        tokenizer = null
        initTried = false
    }

    // ---- Provider-agnostic math + (de)serialisation helpers (unchanged) ----

    /** Cosine similarity of two vectors (unit vectors → just the dot product). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
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
