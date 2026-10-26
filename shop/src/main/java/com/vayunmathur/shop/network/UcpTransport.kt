package com.vayunmathur.shop.network

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.NetworkDataStream
import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.library.network.SimpleResponse
import com.vayunmathur.shop.data.UcpError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal data class UcpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val followRedirects: Boolean = false,
)

internal interface UcpTransport {
    suspend fun execute(request: UcpRequest): RawResponse
    suspend fun stream(request: UcpRequest, block: suspend (NetworkDataStream?, SimpleResponse) -> Unit): SimpleResponse
}

internal object UcpNetworkTransport : UcpTransport {
    override suspend fun execute(request: UcpRequest): RawResponse {
        // Buffer through the capped streaming reader: shared execute's binary path is unbounded.
        var bytes = ByteArray(0)
        val response = stream(request) { input, response ->
            bytes = if (input == null) response.body.toByteArray(Charsets.UTF_8)
                else UcpWire.readBody(input).toByteArray(Charsets.UTF_8)
            require(bytes.size <= UcpWire.MAX_BODY_BYTES) { "Merchant body exceeds limit" }
        }
        return RawResponse(response.status, response.statusMessage, bytes, response.headers, response.url)
    }

    override suspend fun stream(request: UcpRequest, block: suspend (NetworkDataStream?, SimpleResponse) -> Unit): SimpleResponse {
        UcpWire.checkRequest(request)
        return NetworkClient.stream(
            request.url, request.method, request.headers, request.body,
            connectTimeoutMs = 10_000, readTimeoutMs = 10_000,
            followRedirects = request.followRedirects, block = block,
        )
    }
}

internal object UcpWire {
    const val MAX_BODY_BYTES = 1024 * 1024
    const val MAX_EVENT_BYTES = 64 * 1024
    const val MAX_EVENTS = 256
    const val CALL_TIMEOUT_MS = 30_000L

    fun checkRequest(request: UcpRequest) {
        require((request.body?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= MAX_BODY_BYTES) { "Request exceeds limit" }
        require(request.headers.values.none { it.contains('\r') || it.contains('\n') }) { "Invalid request header" }
    }

    fun checkStatus(status: Int) {
        if (status !in 200..299) throw UcpHttpException(
            if (status == 429) UcpError.RateLimited(null) else UcpError.Http(status, ""),
        )
    }

    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")

    suspend fun readBody(stream: NetworkDataStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = stream.read(buffer)
            if (count < 0) break
            require(out.size() + count <= MAX_BODY_BYTES) { "Merchant body exceeds limit" }
            out.write(buffer, 0, count)
        }
        return utf8(out.toByteArray())
    }
}
