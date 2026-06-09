package com.vayunmathur.messages.gmessages

import android.util.Base64
import android.util.Log
import authentication.Authentication
import authentication.Authentication.AuthMessage
import client.Client.StartMediaUploadRequest
import client.Client.UploadMediaResponse
import conversations.Conversations.MediaContent
import conversations.Conversations.MediaFormats
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

/**
 * Port of `pkg/libgm/media.go` (mautrix-gmessages).
 *
 * MMS image send flow:
 *   1. Generate a fresh 32-byte AES-GCM key.
 *   2. Encrypt the bytes with the chunked-GCM stream framing from
 *      [AesGcm.encryptData].
 *   3. POST a START-UPLOAD with `x-goog-upload-command: start` and a
 *      base64'd [StartMediaUploadRequest] envelope as body. The
 *      response Content-Type isn't used — we read the
 *      `x-goog-upload-url` header and other resumable-upload metadata.
 *   4. POST the encrypted bytes to that URL with
 *      `x-goog-upload-command: upload, finalize` and offset 0. The
 *      response body is base64'd `UploadMediaResponse` proto carrying
 *      the assigned `mediaID`.
 *   5. Return a [MediaContent] referencing that media ID + the
 *      decryption key — the caller stuffs this into the
 *      [conversations.Conversations.MessageInfo.MediaContent] field of
 *      a SendMessageRequest.
 *
 * The Messages-for-Web client decrypts on download using the
 * [MediaContent.decryptionKey] we route through the SendMessageRequest.
 */
class Media(private val authProvider: () -> AuthData) {

    private val http: HttpClient = HttpClient(CIO) {
        engine { requestTimeout = 120_000 }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }

    fun close() {
        runCatching { http.close() }
    }

    /** End-to-end: encrypt + start + finalize, return [MediaContent]. */
    suspend fun upload(data: ByteArray, fileName: String, mime: String): MediaContent {
        val format = mimeToFormat(mime)
        val key = AesGcm.generateKey()
        val cipher = AesGcm(key)
        val encrypted = cipher.encryptData(data)
        val start = startUpload(encrypted, mime)
        val finalized = finalizeUpload(start)
        Log.i(TAG, "media upload ok mime=$mime size=${data.size} mediaID=${finalized.mediaID}")
        return MediaContent.newBuilder()
            .setFormat(format)
            .setMediaID(finalized.mediaID)
            .setMediaName(fileName)
            .setSize(data.size.toLong())
            .setDecryptionKey(com.google.protobuf.ByteString.copyFrom(key))
            .setMimeType(mime)
            .build()
    }

    /** Encode + base64 a StartMediaUploadRequest envelope. */
    private fun buildStartPayload(): String {
        val auth = authProvider()
        val req = StartMediaUploadRequest.newBuilder()
            .setAttachmentType(1)
            .setAuthData(
                AuthMessage.newBuilder()
                    .setRequestID(java.util.UUID.randomUUID().toString())
                    .setTachyonAuthToken(
                        com.google.protobuf.ByteString.copyFrom(
                            auth.tachyonToken()
                                ?: error("tachyon token is null — not paired or token expired")
                        )
                    )
                    .setNetwork(PairFlow.QrNetwork)
                    .setConfigVersion(PairFlow.ConfigVersion)
            )
        auth.mobile()?.let { req.setMobile(it) }
        return Base64.encodeToString(req.build().toByteArray(), Base64.NO_WRAP)
    }

    /** POST the START upload request. Returns the resumable URL + meta. */
    private suspend fun startUpload(encrypted: ByteArray, mime: String): StartedUpload {
        val payload = buildStartPayload()
        val sizeStr = encrypted.size.toString()
        val resp: HttpResponse = http.request(Endpoints.UploadMediaUrl) {
            method = HttpMethod.Post
            contentType(ContentType.parse("application/x-www-form-urlencoded;charset=UTF-8"))
            uploadHeaders(
                contentLength = sizeStr,
                command = "start",
                offset = null,
                contentMime = mime,
                protocol = "resumable",
            )
            setBody(payload.toByteArray(Charsets.UTF_8))
        }
        if (resp.status.value !in 200..299) {
            error("start-upload HTTP ${resp.status.value}")
        }
        val uploadUrl = resp.headers["x-goog-upload-url"]
            ?: error("start-upload: missing x-goog-upload-url")
        return StartedUpload(
            uploadUrl = uploadUrl,
            chunkGranularity = resp.headers["x-goog-upload-chunk-granularity"]?.toLongOrNull() ?: 0L,
            mime = mime,
            encrypted = encrypted,
        )
    }

    /** POST the encrypted bytes to the resumable URL and decode the response. */
    private suspend fun finalizeUpload(start: StartedUpload): FinalizedUpload {
        val resp: HttpResponse = http.request(start.uploadUrl) {
            method = HttpMethod.Post
            uploadHeaders(
                contentLength = start.encrypted.size.toString(),
                command = "upload, finalize",
                offset = "0",
                contentMime = start.mime,
                protocol = null,
            )
            setBody(start.encrypted)
        }
        if (resp.status.value !in 200..299) {
            error("finalize-upload HTTP ${resp.status.value}")
        }
        var bodyBytes = resp.bodyAsBytes()
        // The response may be base64-encoded protobuf or raw protobuf.
        // Use Content-Type to decide rather than inspecting the bytes.
        val isBinaryContent = resp.contentType()?.let { ct ->
            ct.contentType == "application"
        } ?: false
        if (!isBinaryContent) {
            bodyBytes = runCatching {
                Base64.decode(bodyBytes, Base64.NO_WRAP)
            }.getOrDefault(bodyBytes)
        }
        val parsed = UploadMediaResponse.parseFrom(bodyBytes)
        return FinalizedUpload(
            mediaID = parsed.media.mediaID,
            mediaNumber = parsed.media.mediaNumber,
        )
    }

    companion object {
     * Apply the upload header bundle (port of util/func.go.NewMediaUploadHeaders).
     * The relay's anti-abuse layer inspects these alongside the URL +
     * Origin to decide whether the request looks like a real browser.
     */
    private fun io.ktor.client.request.HttpRequestBuilder.uploadHeaders(
        contentLength: String,
        command: String?,
        offset: String?,
        contentMime: String?,
        protocol: String?,
    ) {
        headers {
            append("sec-ch-ua", Endpoints.SecUA)
            if (protocol != null) append("x-goog-upload-protocol", protocol)
            append("x-goog-upload-header-content-length", contentLength)
            append("sec-ch-ua-mobile", Endpoints.SecUAMobile)
            append("user-agent", Endpoints.UserAgent)
            if (contentMime != null) append("x-goog-upload-header-content-type", contentMime)
            if (command != null) append("x-goog-upload-command", command)
            if (offset != null) append("x-goog-upload-offset", offset)
            append("sec-ch-ua-platform", "\"${Endpoints.UAPlatform}\"")
            append("accept", "*/*")
            append("origin", "https://messages.google.com")
            append("sec-fetch-site", "cross-site")
            append("sec-fetch-mode", "cors")
            append("sec-fetch-dest", "empty")
            append("referer", "https://messages.google.com/")
            append("accept-language", "en-US,en;q=0.9")
        }
    }

    /**
     * MIME-string → wire-enum mapping. Mirrors libgm's `MimeToMediaType`
     * map; only the formats we'll plausibly emit from the Compose
     * picker are listed. Falls back to the *_UNSPECIFIED bucket for the
     * top-level type so the relay still accepts the upload.
     */
    private fun mimeToFormat(mime: String): MediaFormats = when (mime.lowercase()) {
        "image/jpeg" -> MediaFormats.IMAGE_JPEG
        "image/jpg" -> MediaFormats.IMAGE_JPG
        "image/png" -> MediaFormats.IMAGE_PNG
        "image/gif" -> MediaFormats.IMAGE_GIF
        "image/wbmp" -> MediaFormats.IMAGE_WBMP
        "image/bmp", "image/x-ms-bmp" -> MediaFormats.IMAGE_X_MS_BMP
        "video/mp4" -> MediaFormats.VIDEO_MP4
        "video/3gpp" -> MediaFormats.VIDEO_3GPP
        "video/3gpp2" -> MediaFormats.VIDEO_3G2
        "video/webm" -> MediaFormats.VIDEO_WEBM
        "video/x-matroska" -> MediaFormats.VIDEO_MKV
        "audio/aac" -> MediaFormats.AUDIO_AAC
        "audio/mp4" -> MediaFormats.AUDIO_MP4
        "audio/mpeg" -> MediaFormats.AUDIO_MPEG
        "audio/ogg" -> MediaFormats.AUDIO_OGG
        else -> when (mime.substringBefore('/').lowercase()) {
            "image" -> MediaFormats.IMAGE_UNSPECIFIED
            "video" -> MediaFormats.VIDEO_UNSPECIFIED
            "audio" -> MediaFormats.AUDIO_UNSPECIFIED
            else -> MediaFormats.APP_UNSPECIFIED
        }
    }

    private data class StartedUpload(
        val uploadUrl: String,
        val chunkGranularity: Long,
        val mime: String,
        val encrypted: ByteArray,
    )

    private data class FinalizedUpload(
        val mediaID: String,
        val mediaNumber: Long,
    )

    companion object {
        private const val TAG = "GMessages/Media"
    }
}
