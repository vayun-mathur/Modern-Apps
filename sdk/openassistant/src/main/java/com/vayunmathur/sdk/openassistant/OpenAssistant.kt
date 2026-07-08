package com.vayunmathur.sdk.openassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import com.vayunmathur.library.util.SecureResultReceiver
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenAssistant(private val context: Context, private val timeoutMs: Long = 60_000L) {

    companion object {
        private const val OA_PACKAGE = "com.vayunmathur.openassistant"
        private const val OA_SERVICE = "$OA_PACKAGE.util.InferenceService"

        /**
         * Lowest OpenAssistant `versionCode` that ships the embedding provider
         * (SigLIP2 image/text embedding over [InferenceService]). Must stay in
         * lockstep with the OA app's `versionCode` (root `version.txt`) at the
         * build that first shipped embedding support. Any OA older than this
         * would silently ignore an embed intent, so we detect it up front (no
         * timeout) and report [EmbeddingSupport.NEEDS_UPDATE].
         */
        const val MIN_EMBED_VERSION_CODE = 20260708L

        /** Embedding runs can involve a first-time model load; allow more time. */
        private const val EMBED_TIMEOUT_MS = 120_000L
    }

    /** Result of the fast, synchronous embedding-capability probe. */
    enum class EmbeddingSupport {
        /** OpenAssistant is not installed. */
        NOT_INSTALLED,

        /** Installed but too old to provide embeddings (< [MIN_EMBED_VERSION_CODE]). */
        NEEDS_UPDATE,

        /** Installed and recent enough; embedding requests will be accepted. */
        READY,
    }

    /** Model identity behind the embeddings, for change-detection by callers. */
    data class EmbeddingInfo(val modelId: String, val dim: Int)

    suspend fun generate(prompt: String): String {
        if (!isAvailable()) throw AssistantNotInstalledException()
        return withTimeout(timeoutMs) {
            dispatchInference(prompt, schema = null)
        }
    }

    suspend fun generateJson(prompt: String, schema: String): String {
        if (!isAvailable()) throw AssistantNotInstalledException()
        return withTimeout(timeoutMs) {
            dispatchInference(prompt, schema)
        }
    }

    fun isAvailable(): Boolean {
        return try {
            context.packageManager.getPackageInfo(OA_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fast (no IPC round-trip) check of whether OpenAssistant can serve
     * embeddings, based purely on the installed package's `versionCode`. This is
     * what makes "needs update" instant instead of waiting for a timeout.
     */
    fun embeddingSupport(): EmbeddingSupport {
        val info = try {
            context.packageManager.getPackageInfo(OA_PACKAGE, 0)
        } catch (_: Exception) {
            return EmbeddingSupport.NOT_INSTALLED
        }
        // minSdk 31, so longVersionCode is always available.
        return if (info.longVersionCode >= MIN_EMBED_VERSION_CODE) {
            EmbeddingSupport.READY
        } else {
            EmbeddingSupport.NEEDS_UPDATE
        }
    }

    /**
     * Embed a search phrase into an L2-normalised SigLIP2 text vector, in the
     * same shared space as [embedImage].
     *
     * @throws AssistantNotInstalledException OpenAssistant is not installed.
     * @throws EmbeddingUnsupportedException OpenAssistant is too old.
     * @throws EmbeddingModelDownloadingException the models are still downloading.
     * @throws AssistantException any other failure.
     */
    suspend fun embedText(query: String): FloatArray {
        requireEmbeddingReady()
        return withTimeout(EMBED_TIMEOUT_MS) {
            dispatchEmbedding(mode = "text", text = query, uri = null).embedding
        }
    }

    /**
     * Embed an image (given by a readable `content://` [uri]) into an
     * L2-normalised SigLIP2 image vector. OpenAssistant decodes the image, so
     * the caller does not decode a bitmap. Same failure modes as [embedText].
     */
    suspend fun embedImage(uri: Uri): FloatArray {
        requireEmbeddingReady()
        return withTimeout(EMBED_TIMEOUT_MS) {
            dispatchEmbedding(mode = "image", text = null, uri = uri).embedding
        }
    }

    /**
     * Probe the embedding provider for its model id and dimensionality without
     * running an embedding. Callers use [EmbeddingInfo.modelId] to detect a
     * model change (and re-index) and [EmbeddingInfo.dim] for sizing.
     *
     * Same failure modes as [embedText] (including
     * [EmbeddingModelDownloadingException] if the models aren't ready yet).
     */
    suspend fun embeddingInfo(): EmbeddingInfo {
        requireEmbeddingReady()
        return withTimeout(timeoutMs) {
            val result = dispatchEmbedding(mode = "info", text = null, uri = null)
            EmbeddingInfo(result.modelId, result.dim)
        }
    }

    suspend fun close() {
        // No persistent connection to clean up; reserved for future use.
    }

    private fun requireEmbeddingReady() {
        when (embeddingSupport()) {
            EmbeddingSupport.NOT_INSTALLED -> throw AssistantNotInstalledException()
            EmbeddingSupport.NEEDS_UPDATE -> throw EmbeddingUnsupportedException()
            EmbeddingSupport.READY -> {}
        }
    }

    private suspend fun dispatchInference(prompt: String, schema: String?): String =
        suspendCancellableCoroutine { cont ->
            val receiver = SecureResultReceiver(Handler(Looper.getMainLooper())) { code, data ->
                if (code == 0) {
                    val result = data?.getString("json_result") ?: ""
                    cont.resume(result)
                } else {
                    val error = data?.getString("error") ?: "Inference failed"
                    cont.resumeWithException(AssistantException(error))
                }
            }

            val intent = Intent().apply {
                component = ComponentName(OA_PACKAGE, OA_SERVICE)
                putExtra("user_text", prompt)
                putExtra("schema", schema ?: """{"type":"object","properties":{"response":{"type":"string"}},"required":["response"]}""")
                putExtra("RECEIVER", receiver as ResultReceiver)
            }

            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                cont.resumeWithException(AssistantException("Failed to start InferenceService: ${e.message}"))
            }
        }

    /** Raw embedding result parsed from a success bundle (code 0). */
    private class EmbedResult(val embedding: FloatArray, val modelId: String, val dim: Int)

    private suspend fun dispatchEmbedding(mode: String, text: String?, uri: Uri?): EmbedResult =
        suspendCancellableCoroutine { cont ->
            val receiver = SecureResultReceiver(Handler(Looper.getMainLooper())) { code, data ->
                when (code) {
                    0 -> {
                        val modelId = data?.getString("model_id") ?: ""
                        val dim = data?.getInt("dim") ?: 0
                        // "info" requests carry no vector; text/image requests do.
                        val bytes = data?.getByteArray("embedding")
                        if (mode != "info" && bytes == null) {
                            cont.resumeWithException(AssistantException("Embedding result missing vector"))
                        } else {
                            cont.resume(EmbedResult(bytes?.let { bytesToFloats(it) } ?: FloatArray(0), modelId, dim))
                        }
                    }
                    2 -> {
                        val progress = data?.getDouble("progress") ?: 0.0
                        cont.resumeWithException(EmbeddingModelDownloadingException(progress))
                    }
                    else -> {
                        val error = data?.getString("error") ?: "Embedding failed"
                        cont.resumeWithException(AssistantException(error))
                    }
                }
            }

            val intent = Intent().apply {
                component = ComponentName(OA_PACKAGE, OA_SERVICE)
                putExtra("embed_mode", mode)
                if (text != null) putExtra("user_text", text)
                if (uri != null) {
                    // Single content:// URI in an ArrayList<Uri>, granted read so
                    // the exported service can openInputStream it (copyUriToFile).
                    putParcelableArrayListExtra("image_uris", arrayListOf(uri))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                putExtra("RECEIVER", receiver as ResultReceiver)
            }

            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                cont.resumeWithException(AssistantException("Failed to start InferenceService: ${e.message}"))
            }
        }

    private fun bytesToFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) out[i] = buffer.float
        return out
    }
}
