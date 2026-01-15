package com.vayunmathur.openassistant

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class GrokApi(private val apiKey: String) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        engine {
            requestTimeout = 0 // No timeout for streaming
        }
    }

    class GrokException(val errorNum: Int, body: String): Exception("Error $errorNum: $body")

    fun getGrokCompletionStream(request: GrokRequest, showToast: (String) -> Unit): Flow<GrokChunk> = flow {
        try {
            val response = client.post("https://api.x.ai/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(request.copy(model = "grok-4-fast-reasoning", stream = true))
            }

            if (!response.status.isSuccess()) {
                throw GrokException(response.status.value, response.bodyAsText())
            }

            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line()
                if (line?.startsWith("data: ") == true) {
                    val jsonString = line.substring(6)
                    if (jsonString != "[DONE]") {
                        emit(json.decodeFromString<GrokChunk>(jsonString))
                    }
                }
            }
        } catch(_: Exception) {
            showToast("Unable to connect")
        }
    }
}