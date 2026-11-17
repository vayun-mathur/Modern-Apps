package com.vayunmathur.youpipe.network

import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.downloader.StreamingResponse
import org.schabi.newpipe.extractor.localization.Localization

/**
 * YouPipe downloader – migrated from Ktor/OkHttp to Android-only NetworkClient.
 *
 * Non-streaming [execute] uses NetworkClient.performRequest (HttpURLConnection).
 * Streaming SABR transfers previously used a dedicated OkHttp sabrClient because
 * Ktor forced Content-Type: application/octet-stream. HttpURLConnection does NOT
 * force Content-Type when body is null, so [NetworkClient.performRequestInputStream]
 * is now sufficient and exposes true streaming InputStream with redirect follow.
 */
class MyDownloader : Downloader() {
    override fun execute(request: Request): Response = runBlocking {
        val url = request.url
        val method = request.httpMethod
        val body = request.dataToSend

        val response = NetworkClient.performRequest(
            url = url,
            method = method,
            headers = withDefaultHeaders(request.headers),
            body = body
        )

        Response(
            response.status,
            response.statusMessage,
            response.headers,
            response.body,
            response.url
        )
    }

    override fun getStreaming(
        url: String,
        headers: Map<String, List<String>>?,
        localization: Localization?
    ): StreamingResponse = runBlocking {
        executeStreamingSync(url, "GET", headers, null, timeoutMs = null)
    }

    override fun getStreaming(
        url: String,
        headers: Map<String, List<String>>?,
        localization: Localization?,
        timeoutMs: Long
    ): StreamingResponse = runBlocking {
        executeStreamingSync(url, "GET", headers, null, timeoutMs.coerceAtLeast(1))
    }

    override fun postStreaming(
        url: String,
        headers: Map<String, List<String>>?,
        dataToSend: ByteArray?,
        localization: Localization?
    ): StreamingResponse = runBlocking {
        executeStreamingSync(url, "POST", headers, dataToSend, timeoutMs = null)
    }

    private suspend fun executeStreamingSync(
        url: String,
        method: String,
        headers: Map<String, List<String>>?,
        data: ByteArray?,
        timeoutMs: Long?
    ): StreamingResponse {
        val hdr = mutableMapOf<String, MutableList<String>>()
        val hasUserAgent = headers?.keys?.any { it.equals("User-Agent", ignoreCase = true) } == true
        if (!hasUserAgent) {
            hdr["User-Agent"] = mutableListOf(DEFAULT_USER_AGENT)
        }
        headers?.forEach { (name, values) ->
            hdr[name] = values.toMutableList()
        }

        // Use new streaming API – body may be null; null body = no Content-Type forced.
        val (code, respHeaders, stream) = NetworkClient.performRequestInputStream(
            url = url,
            method = method,
            headers = hdr,
            body = data,
            timeoutMs = timeoutMs
        )
        return StreamingResponse(code, respHeaders, stream)
    }

    private fun withDefaultHeaders(
        headers: Map<String, List<String>>?
    ): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        if (headers != null) result.putAll(headers)
        if (result.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            result["User-Agent"] = listOf(DEFAULT_USER_AGENT)
        }
        return result
    }

    /** Shared constants and helpers for MyDownloader. */
    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
