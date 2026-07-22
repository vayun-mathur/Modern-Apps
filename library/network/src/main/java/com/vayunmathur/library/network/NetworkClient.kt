package com.vayunmathur.library.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpMethod
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json

data class SimpleResponse(
    val status: Int,
    val statusMessage: String,
    val body: String,
    val headers: Map<String, List<String>>,
    val url: String,
) {
    val isSuccess: Boolean get() = status in 200..299
    val contentLength: Long? get() = headers["Content-Length"]?.firstOrNull()?.toLongOrNull()
}

interface NetworkDataStream {
    suspend fun read(buffer: ByteArray): Int
    val isClosedForRead: Boolean
}

object NetworkClient {
    private val jsonConfig = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
    }

    val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 30_000
        }
        install(ContentNegotiation) {
            json(jsonConfig)
        }
    }

    @PublishedApi
    internal fun HttpRequestBuilder.applyHeaders(headers: Map<String, *>) {
        headers.forEach { (key, value) ->
            when (value) {
                is Iterable<*> -> value.forEach { v -> if (v != null) header(key, v) }
                else -> if (value != null) header(key, value)
            }
        }
    }

    private fun HttpResponse.toSimpleResponse(body: String = ""): SimpleResponse {
        return SimpleResponse(
            status = status.value,
            statusMessage = status.description,
            body = body,
            headers = headers.entries().associate { it.key to it.value },
            url = request.url.toString()
        )
    }

    suspend fun performRequestBytes(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null
    ): Pair<Int, ByteArray> {
        val response: HttpResponse = client.request(url) {
            this.method = HttpMethod.parse(method)
            applyHeaders(headers)
            body?.let { setBody(it) }
        }
        return response.status.value to response.body<ByteArray>()
    }

    /**
     * Like [performRequestBytes] but also returns the response headers. Used by SABR streaming,
     * which needs the raw (binary-safe) body plus headers (e.g. Content-Type) in one request.
     */
    suspend fun performRequestBytesFull(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null
    ): Triple<Int, Map<String, List<String>>, ByteArray> {
        val response: HttpResponse = client.request(url) {
            this.method = HttpMethod.parse(method)
            applyHeaders(headers)
            body?.let { setBody(it) }
        }
        val respHeaders = response.headers.entries().associate { it.key to it.value }
        return Triple(response.status.value, respHeaders, response.body<ByteArray>())
    }

    suspend fun performRequest(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null
    ): SimpleResponse {
        val response: HttpResponse = client.request(url) {
            this.method = HttpMethod.parse(method)
            applyHeaders(headers)
            body?.let { setBody(it) }
        }
        return response.toSimpleResponse(response.bodyAsText())
    }

    suspend fun stream(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        block: suspend (stream: NetworkDataStream?, response: SimpleResponse) -> Unit
    ): SimpleResponse {
        return client.prepareRequest(url) {
            this.method = HttpMethod.parse(method)
            applyHeaders(headers)
        }.execute { response ->
            val simpleResponse = response.toSimpleResponse()
            if (simpleResponse.isSuccess || simpleResponse.status == 206) {
                val channel = response.bodyAsChannel()
                val stream = object : NetworkDataStream {
                    override suspend fun read(buffer: ByteArray): Int = channel.readAvailable(buffer)
                    override val isClosedForRead: Boolean get() = channel.isClosedForRead
                }
                block(stream, simpleResponse)
            } else {
                block(null, simpleResponse)
            }
            simpleResponse
        }
    }

    suspend fun getContentLength(url: String, headers: Map<String, *> = emptyMap<String, Any>()): Long? {
        val response = client.request(url) {
            method = HttpMethod.Head
            applyHeaders(headers)
        }
        return response.headers["Content-Length"]?.toLongOrNull()
            ?: response.headers["Content-Range"]?.substringAfterLast("/")?.toLongOrNull()
    }

    suspend inline fun <reified T> callJson(
        url: String,
        method: String = "GET",
        headers: Map<String, *> = emptyMap<String, Any>(),
        body: Any? = null
    ): T {
        val response = client.request(url) {
            this.method = HttpMethod.parse(method)
            applyHeaders(headers)
            body?.let { setBody(it) }
        }
        // Calling .body<T>() on a 204 No Content / 304 / empty-body response
        // throws Ktor's NoTransformationFoundException, which callers usually
        // catch and silently treat as "the call failed" — but it actually
        // means the server successfully responded with no payload. For the
        // T == Boolean case the answer should be `true` (success), and for
        // nullable / collection T the answer should be null/empty rather
        // than blowing up.
        if (response.status.value == 204 || response.contentLength() == 0L) {
            // The most common T's are Boolean (success/failure), List<...>,
            // Map<...>, and nullable data classes. We can't conjure a value
            // for an arbitrary T, but we can short-circuit the predictable
            // success-but-empty case by checking the type token.
            val tType = T::class
            @Suppress("UNCHECKED_CAST")
            when (tType) {
                Boolean::class -> return (response.status.value in 200..299) as T
                Unit::class -> return Unit as T
            }
        }
        if (!response.status.isSuccess()) {
            throw io.ktor.client.plugins.ResponseException(response, "HTTP ${response.status.value}")
        }
        return response.body()
    }

    suspend inline fun <reified T> getJson(
        url: String,
        headers: Map<String, *> = emptyMap<String, Any>()
    ): T = callJson(url, "GET", headers)
}
