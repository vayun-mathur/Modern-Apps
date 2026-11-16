package com.vayunmathur.communicate.data.signal.transport

import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.net.ssl.SSLSocketFactory

/**
 * Attachment upload.
 *
 * Two steps: ask the chat server for an upload form
 * (`GET /v4/attachments/form/upload?uploadLength=N`), then push the already-encrypted bytes to the CDN
 * it names. CDN3 is TUS "creation with upload" — a single POST to the signed location carrying
 * `Upload-Length` and `Tus-Resumable: 1.0.0`. The form's own headers must be replayed, minus `host`.
 *
 * The bytes handed here are expected to be [com.vayunmathur.communicate.data.signal.SignalAttachmentCipher]
 * output; this class never sees plaintext.
 */
object SignalAttachmentUpload {
    /** Test seam: localhost base replacing the fixed chat host. */
    internal var chatBaseForTest: String? = null
    private const val TAG = "SignalAttachmentUpload"

    private val json = Json { ignoreUnknownKeys = true }

    /** The signed upload form for a Signal attachment: CDN, key and headers. */
    data class UploadForm(
        val cdn: Int,
        /** Becomes `AttachmentPointer.cdnKey`. */
        val key: String,
        val headers: Map<String, String>,
        val signedUploadLocation: String,
    )

    /** Fetch an upload form for a blob of [uploadLength] bytes, or null when the server refuses. */
    suspend fun fetchForm(
        uploadLength: Int,
        authHeader: String,
        sslSocketFactory: SSLSocketFactory?,
    ): UploadForm? {
        val resp = try {
            NetworkClient.execute(
                "${chatBaseForTest ?: "https://chat.signal.org"}/v4/attachments/form/upload?uploadLength=$uploadLength",
                method = "GET",
                headers = mapOf("Authorization" to "Basic $authHeader"),
                sslSocketFactory = sslSocketFactory,
            )
        } catch (expected: Throwable) {
            Log.status(TAG, "upload form fetch failed", expected)
            return null
        }
        if (!resp.isSuccess) {
            Log.status(TAG, "upload form fetch failed: ${resp.status} ${resp.statusMessage}")
            return null
        }
        return parseForm(resp.text)
    }

    internal fun parseForm(body: String, warn: (String) -> Unit = { Log.status(TAG, it) }): UploadForm? {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (expected: Exception) {
            warn("unparseable upload form: ${expected.message}")
            return null
        }
        val cdn = try { root["cdn"]?.jsonPrimitive?.int } catch (_: Exception) { null }
        val key = try { root["key"]?.jsonPrimitive?.content } catch (_: Exception) { null }
        val location = try { root["signedUploadLocation"]?.jsonPrimitive?.content } catch (_: Exception) { null }
        if (cdn == null || key.isNullOrEmpty() || location.isNullOrEmpty()) {
            warn("upload form was missing cdn, key or signedUploadLocation")
            return null
        }
        val headers = try {
            root["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        return UploadForm(cdn = cdn, key = key, headers = headers, signedUploadLocation = location)
    }

    /**
     * Upload [blob] according to [form]. Returns whether the CDN accepted it.
     *
     * Only CDN3 is implemented; CDN2 uses a different create-then-PUT sequence, so an unexpected version
     * is refused rather than uploaded with the wrong protocol.
     */
    suspend fun upload(
        form: UploadForm,
        blob: ByteArray,
        sslSocketFactory: SSLSocketFactory?,
    ): Boolean {
        if (form.cdn != CDN3) {
            Log.status(TAG, "unsupported CDN version ${form.cdn}, not uploading")
            return false
        }
        val headers = buildMap {
            // The form's headers carry the upload authorisation; host is set by the connection.
            form.headers.forEach { (name, value) -> if (!name.equals("host", true)) put(name, value) }
            put("Tus-Resumable", TUS_VERSION)
            put("Upload-Length", blob.size.toString())
            put("Content-Type", "application/offset+octet-stream")
        }
        return try {
            val resp = NetworkClient.execute(
                form.signedUploadLocation,
                method = "POST",
                headers = headers,
                body = blob,
                sslSocketFactory = sslSocketFactory,
            )
            if (!resp.isSuccess) Log.status(TAG, "attachment upload rejected: ${resp.status} ${resp.statusMessage}")
            resp.isSuccess
        } catch (expected: Throwable) {
            Log.status(TAG, "attachment upload failed", expected)
            false
        }
    }

    private const val CDN3 = 3
    private const val TUS_VERSION = "1.0.0"
}
