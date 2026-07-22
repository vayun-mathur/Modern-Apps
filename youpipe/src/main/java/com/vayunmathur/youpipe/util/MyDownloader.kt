package com.vayunmathur.youpipe.util
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.downloader.StreamingResponse
import org.schabi.newpipe.extractor.localization.Localization
import java.io.ByteArrayInputStream

class MyDownloader : Downloader() {
    override fun execute(request: Request): Response = runBlocking {
        val url = request.url()
        val method = request.httpMethod()
        val body = request.dataToSend()

        val response = NetworkClient.performRequest(
            url = url,
            method = method,
            headers = request.headers(),
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

    // SABR needs binary-safe streaming bodies. NetworkClient.performRequestBytesFull returns the
    // raw bytes + headers in a single request; we expose them as an InputStream-backed
    // StreamingResponse. (This buffers the response; SABR requests are byte-range bounded by the
    // session, so the batches stay reasonably sized.)
    override fun getStreaming(
        url: String,
        headers: MutableMap<String, MutableList<String>>?,
        localization: Localization?
    ): StreamingResponse = runBlocking {
        val (status, respHeaders, bytes) = NetworkClient.performRequestBytesFull(
            url = url,
            method = "GET",
            headers = headers ?: emptyMap<String, List<String>>()
        )
        StreamingResponse(status, respHeaders, ByteArrayInputStream(bytes))
    }

    override fun getStreaming(
        url: String,
        headers: MutableMap<String, MutableList<String>>?,
        localization: Localization?,
        timeoutMs: Long
    ): StreamingResponse = getStreaming(url, headers, localization)

    override fun postStreaming(
        url: String,
        headers: MutableMap<String, MutableList<String>>?,
        dataToSend: ByteArray?,
        localization: Localization?
    ): StreamingResponse = runBlocking {
        val (status, respHeaders, bytes) = NetworkClient.performRequestBytesFull(
            url = url,
            method = "POST",
            headers = headers ?: emptyMap<String, List<String>>(),
            body = dataToSend
        )
        StreamingResponse(status, respHeaders, ByteArrayInputStream(bytes))
    }
}
