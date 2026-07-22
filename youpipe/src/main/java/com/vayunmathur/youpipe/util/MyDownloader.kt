package com.vayunmathur.youpipe.util
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.downloader.StreamingResponse
import org.schabi.newpipe.extractor.localization.Localization
import java.util.concurrent.TimeUnit

class MyDownloader : Downloader() {
    override fun execute(request: Request): Response = runBlocking {
        val url = request.url()
        val method = request.httpMethod()
        val body = request.dataToSend()

        val response = NetworkClient.performRequest(
            url = url,
            method = method,
            headers = withDefaultHeaders(request.headers()),
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

    // SABR streaming: faithful port of PipePipe's DownloaderImpl.executeStreaming using OkHttp
    // directly. Ktor's setBody(ByteArray) forces Content-Type: application/octet-stream and its
    // ContentNegotiation(json) sits on the binary path — both of which broke the SABR
    // VideoPlaybackAbrRequest POST (server returned no media). OkHttp with a null-media-type
    // RequestBody sends NO Content-Type (matching PipePipe), exposes the body as a true streaming
    // InputStream (byteStream), and follows redirects — so large media batches aren't buffered.
    override fun getStreaming(
        url: String,
        headers: MutableMap<String, MutableList<String>>?,
        localization: Localization?
    ): StreamingResponse = executeStreaming(url, "GET", headers, null, sabrClient)

    override fun getStreaming(
        url: String,
        headers: MutableMap<String, MutableList<String>>?,
        localization: Localization?,
        timeoutMs: Long
    ): StreamingResponse {
        val bounded = timeoutMs.coerceAtLeast(1)
        val client = sabrClient.newBuilder()
            .callTimeout(bounded, TimeUnit.MILLISECONDS)
            .connectTimeout(bounded, TimeUnit.MILLISECONDS)
            .readTimeout(bounded, TimeUnit.MILLISECONDS)
            .build()
        return executeStreaming(url, "GET", headers, null, client)
    }

    override fun postStreaming(
        url: String,
        headers: MutableMap<String, MutableList<String>>?,
        dataToSend: ByteArray?,
        localization: Localization?
    ): StreamingResponse = executeStreaming(url, "POST", headers, dataToSend, sabrClient)

    private fun executeStreaming(
        url: String,
        method: String,
        headers: Map<String, List<String>>?,
        data: ByteArray?,
        client: OkHttpClient
    ): StreamingResponse {
        // Null media type => OkHttp sends no Content-Type header (matches PipePipe). An empty POST
        // still needs a body so OkHttp allows the method.
        val requestBody = when {
            data != null -> data.toRequestBody(null)
            method == "POST" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val builder = OkRequest.Builder().method(method, requestBody).url(url)
        val hasUserAgent = headers?.keys?.any { it.equals("User-Agent", ignoreCase = true) } == true
        if (!hasUserAgent) {
            builder.header("User-Agent", DEFAULT_USER_AGENT)
        }
        headers?.forEach { (name, values) ->
            when {
                values.size > 1 -> {
                    builder.removeHeader(name)
                    values.forEach { builder.addHeader(name, it) }
                }
                values.size == 1 -> builder.header(name, values[0])
            }
        }
        val response = client.newCall(builder.build()).execute()
        val bodyStream = response.body?.byteStream()
            ?: java.io.ByteArrayInputStream(ByteArray(0))
        // StreamingResponse.close() closes this stream, which closes the OkHttp body + connection.
        return StreamingResponse(response.code, response.headers.toMultimap(), bodyStream)
    }

    // Inject a default browser User-Agent when the caller didn't set one (matches PipePipe's
    // DownloaderImpl). YouTube/googlevideo rejects UA-less requests with HTTP 403.
    private fun withDefaultHeaders(
        headers: Map<String, List<String>>?
    ): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        if (headers != null) {
            result.putAll(headers)
        }
        if (result.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            result["User-Agent"] = listOf(DEFAULT_USER_AGENT)
        }
        return result
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

        // Dedicated OkHttp client for SABR binary transfers: no content negotiation, follows
        // redirects (default), no forced Content-Type. Reused across requests for connection pooling.
        private val sabrClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
