package com.vayunmathur.library.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpMethod
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

    val client = HttpClient(CIO) {
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
        val response = client.request(url) {
            this.method = HttpMethod.parse(method)
            applyHeaders(headers)
        }
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
        return simpleResponse
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
        return client.request(url) {
            this.method = HttpMethod.parse(method)
            applyHeaders(headers)
            body?.let { setBody(it) }
        }.body()
    }

    suspend inline fun <reified T> getJson(
        url: String,
        headers: Map<String, *> = emptyMap<String, Any>()
    ): T = callJson(url, "GET", headers)
}
