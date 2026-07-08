package com.vayunmathur.photos.util

import android.content.Context
import android.net.Uri
import com.vayunmathur.sdk.openassistant.OpenAssistant
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Thin **OpenAssistant-backed** semantic embedder for photo search.
 *
 * Photos no longer runs CLIP on-device. Instead it delegates image/text
 * embedding to the OpenAssistant app (Gemma-based, with a HuggingFace
 * model-download pipeline and an exported, signature-gated `InferenceService`),
 * which serves **SigLIP2-base** vectors. Photos stores the returned vectors on
 * the [com.vayunmathur.photos.data.Photo] row and does cosine locally — exactly
 * as before, only the *producer* of the vectors moved out of process.
 *
 * All cross-app IPC lives in the [OpenAssistant] SDK client; this object is just
 * a small adapter that keeps the old call-site shape (image/text embedding +
 * provider-agnostic vector math) so the workers and the search path barely
 * change. There is no on-device model to load, so there are no assets and no
 * ONNX sessions here anymore.
 *
 * Availability is gated by [embeddingSupport]: OpenAssistant must be installed
 * and recent enough. The models download **on demand** the first time an embed
 * request arrives, during which the embed calls throw
 * [com.vayunmathur.sdk.openassistant.EmbeddingModelDownloadingException].
 */
object ClipEmbedder {

    /**
     * Bump whenever the embedding space changes so [ClipWorker] clears every
     * stored vector and re-indexes. Bumped from 1 (512-d on-device MobileCLIP)
     * to 2 for the move to OpenAssistant's 768-d SigLIP2 space.
     */
    const val EMBEDDER_VERSION = 2

    /** Fast, synchronous capability probe (package + versionCode based). */
    fun embeddingSupport(context: Context): OpenAssistant.EmbeddingSupport =
        OpenAssistant(context).embeddingSupport()

    /**
     * Embed [uri] into an L2-normalised image vector via OpenAssistant. Unlike
     * the old on-device path this takes a **URI** (OpenAssistant decodes it), so
     * photos no longer decodes a bitmap for embedding.
     *
     * @throws com.vayunmathur.sdk.openassistant.AssistantNotInstalledException
     * @throws com.vayunmathur.sdk.openassistant.EmbeddingUnsupportedException
     * @throws com.vayunmathur.sdk.openassistant.EmbeddingModelDownloadingException
     * @throws com.vayunmathur.sdk.openassistant.AssistantException
     */
    suspend fun imageEmbedding(context: Context, uri: Uri): FloatArray =
        OpenAssistant(context).embedImage(uri)

    /**
     * Embed a search [query] into an L2-normalised vector in the SAME space as
     * [imageEmbedding]. Same failure modes as [imageEmbedding].
     */
    suspend fun textEmbedding(context: Context, query: String): FloatArray =
        OpenAssistant(context).embedText(query)

    /** Model identity + dim behind the vectors, for change-detection re-index. */
    suspend fun embeddingInfo(context: Context): OpenAssistant.EmbeddingInfo =
        OpenAssistant(context).embeddingInfo()

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
