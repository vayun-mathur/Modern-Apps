package com.vayunmathur.youpipe

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import kotlin.time.Duration.Companion.seconds

class MyDownloader : Downloader() {
    // Build the client with reasonable timeouts
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15.seconds)
        .readTimeout(15.seconds)
        .build()

    override fun execute(request: Request): Response {
        val url = request.url()
        val method = request.httpMethod()
        val body = request.dataToSend()
        val headersBuilder = Headers.Builder()
        for (entry in request.headers().entries) {
            for (value in entry.value) {
                headersBuilder.add(entry.key, value)
            }
        }
        val headers = headersBuilder.build()

        // Construct the OkHttp Request
        val okHttpRequest = okhttp3.Request.Builder()
            .url(url)
            .method(method, body?.toRequestBody())
            .headers(headers)
            .build()

        client.newCall(okHttpRequest).execute().use { okHttpResponse ->
            val responseCode = okHttpResponse.code
            val responseMessage = okHttpResponse.message
            val responseBody = okHttpResponse.body.string()
            return Response(
                responseCode,
                responseMessage,
                okHttpResponse.headers.toMultimap(),
                responseBody,
                url
            )
        }
    }
}