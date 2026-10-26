package com.vayunmathur.shop.network

import com.vayunmathur.library.network.NetworkDataStream
import com.vayunmathur.shop.data.UcpError
import com.vayunmathur.shop.domain.MerchantUrls
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.URI
import java.util.UUID

internal object UcpMcp {
    private const val MODERN = "2025-03-26"
    private const val LEGACY = "2024-11-05"
    private class LegacyRequired : Exception()

    suspend fun call(wire: UcpTransport, endpoint: String, tool: String, arguments: JsonObject, version: String): JsonObject =
        try { modern(wire, endpoint, tool, arguments, version) }
        catch (_: LegacyRequired) { legacy(wire, endpoint, tool, arguments, version) }

    private fun headers(version: String, session: Map<String, String> = emptyMap()): Map<String, String> = mapOf(
        "UCP-Agent" to "profile=\"${UcpApi.profileUrl(version)}\"",
        "Content-Type" to "application/json",
        "Accept" to "application/json, text/event-stream",
        "Request-Id" to UUID.randomUUID().toString(),
    ) + session

    private fun rpc(method: String, params: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", UUID.randomUUID().toString())
        put("method", method)
        put("params", params)
    }

    private fun initialize(protocol: String) = rpc("initialize", buildJsonObject {
        put("protocolVersion", protocol)
        putJsonObject("capabilities") {}
        putJsonObject("clientInfo") { put("name", "Shop"); put("version", "0.1") }
    })

    private fun notification() = buildJsonObject {
        put("jsonrpc", "2.0"); put("method", "notifications/initialized")
    }

    private fun toolCall(tool: String, arguments: JsonObject, version: String) = rpc("tools/call", buildJsonObject {
        put("name", tool); put("arguments", UcpApi.withAgentProfile(arguments, version))
    })

    private fun checkEnvelope(value: JsonObject) {
        require(value.text("jsonrpc") == "2.0") { "Invalid JSON-RPC version" }
    }

    private fun checkReply(value: JsonObject, id: JsonElement): JsonObject {
        checkEnvelope(value)
        require(value["id"] == id && value.text("method") == null) { "Mismatched MCP response ID" }
        value["error"]?.let { throw UcpHttpException(UcpError.Rpc("Merchant RPC rejected the request")) }
        require(value["result"].asObject() != null) { "Missing RPC result" }
        return value
    }

    private suspend fun send(wire: UcpTransport, endpoint: String, envelope: JsonObject, headers: Map<String, String>) {
        val request = UcpRequest(endpoint, "POST", headers + ("Request-Id" to UUID.randomUUID().toString()), envelope.toString())
        UcpWire.checkRequest(request)
        wire.stream(request) { _, response ->
            UcpWire.checkStatus(response.status)
        }
    }

    private suspend fun serverMessage(wire: UcpTransport, endpoint: String, value: JsonObject, headers: Map<String, String>) {
        checkEnvelope(value)
        val method = value.text("method") ?: return
        val id = value["id"] ?: return // Notifications do not require replies.
        val reply = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id)
            if (method == "ping") putJsonObject("result") {}
            else putJsonObject("error") { put("code", -32601); put("message", "Method not supported") }
        }
        send(wire, endpoint, reply, headers)
    }

    private suspend fun awaitReply(
        reader: UcpSse, wire: UcpTransport, endpoint: String, id: JsonElement, headers: Map<String, String>,
    ): JsonObject {
        while (true) {
            val event = reader.next() ?: throw UcpHttpException(UcpError.Transport("MCP stream ended before response"))
            if (event.event != "message") continue
            val value = UcpApi.parseObject(event.data)
            checkEnvelope(value)
            if (value.text("method") != null) serverMessage(wire, endpoint, value, headers)
            else if (value["id"] == id) return checkReply(value, id)
        }
    }

    private suspend fun postReply(
        wire: UcpTransport, endpoint: String, envelope: JsonObject, headers: Map<String, String>,
        initializing: Boolean = false, onHeaders: (Map<String, List<String>>) -> Unit = {},
    ): JsonObject {
        var result: JsonObject? = null
        val request = UcpRequest(endpoint, "POST", headers + ("Request-Id" to UUID.randomUUID().toString()), envelope.toString())
        UcpWire.checkRequest(request)
        wire.stream(request) { stream, response ->
            if (initializing && response.status in setOf(400, 404, 405, 415, 501)) throw LegacyRequired()
            UcpWire.checkStatus(response.status)
            onHeaders(response.headers)
            requireNotNull(stream) { "Missing MCP response" }
            val type = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            val id = requireNotNull(envelope["id"])
            result = when (type) {
                "application/json" -> checkReply(UcpApi.parseObject(UcpWire.readBody(stream)), id)
                "text/event-stream" -> {
                    val announcedSession = if (initializing) response.header("Mcp-Session-Id") else null
                    val replyHeaders = headers + (announcedSession?.let { mapOf("Mcp-Session-Id" to it) } ?: emptyMap())
                    awaitReply(UcpSse(stream), wire, endpoint, id, replyHeaders)
                }
                else -> throw UcpHttpException(UcpError.Malformed("Unsupported MCP content type"))
            }
        }
        return requireNotNull(result)
    }

    private suspend fun modern(wire: UcpTransport, endpoint: String, tool: String, arguments: JsonObject, version: String): JsonObject {
        var sessionId: String? = null
        var protocol = MODERN
        try {
            val initialized = postReply(wire, endpoint, initialize(MODERN), headers(version), true) { responseHeaders ->
                sessionId = responseHeaders.entries.firstOrNull { it.key.equals("Mcp-Session-Id", true) }
                    ?.value?.firstOrNull()?.also {
                        require(it.isNotBlank() && it.length <= 1024 && it.all { c -> c.code in 33..126 }) { "Invalid MCP session ID" }
                    }
            }["result"].asObject() ?: error("Missing initialization result")
            protocol = initialized.text("protocolVersion") ?: error("Missing MCP protocol version")
            if (protocol == LEGACY) throw LegacyRequired()
            require(protocol in setOf(MODERN, "2025-06-18", "2025-11-25")) { "Unsupported MCP protocol version" }
            val session = mapOf("MCP-Protocol-Version" to protocol) +
                (sessionId?.let { mapOf("Mcp-Session-Id" to it) } ?: emptyMap())
            val sessionHeaders = headers(version, session)
            send(wire, endpoint, notification(), sessionHeaders)
            val reply = postReply(wire, endpoint, toolCall(tool, arguments, version), sessionHeaders)
            return UcpApi.unwrapResult(reply, version, tool)
        } finally {
            sessionId?.let { id ->
                // Cleanup is best effort, but bounded and independent of caller cancellation.
                withContext(NonCancellable) {
                    withTimeoutOrNull(2_000) {
                        try {
                            wire.stream(UcpRequest(endpoint, "DELETE", headers(version, mapOf(
                                "Mcp-Session-Id" to id, "MCP-Protocol-Version" to protocol,
                            )))) { _, _ -> }
                        } catch (_: Exception) { /* Session expiry is merchant-controlled. */ }
                    }
                }
            }
        }
    }

    internal fun legacyEndpoint(endpoint: String, announcement: String): String {
        val origin = URI(endpoint)
        val target = origin.resolve(announcement)
        require(target.fragment == null && MerchantUrls.httpsUrl(target.toString()) != null &&
            target.scheme.equals(origin.scheme, true) && target.host.equals(origin.host, true) &&
            (target.port == -1 || target.port == 443) && (origin.port == -1 || origin.port == 443)) {
            "Unsafe legacy MCP message endpoint"
        }
        return target.toASCIIString()
    }

    private suspend fun legacy(wire: UcpTransport, endpoint: String, tool: String, arguments: JsonObject, version: String): JsonObject {
        var result: JsonObject? = null
        val baseHeaders = headers(version, mapOf("MCP-Protocol-Version" to LEGACY))
        wire.stream(UcpRequest(endpoint, "GET", baseHeaders + ("Accept" to "text/event-stream"))) { stream, response ->
            UcpWire.checkStatus(response.status)
            require(response.header("Content-Type")?.substringBefore(';')?.trim()?.equals("text/event-stream", true) == true) {
                "Expected legacy MCP event stream"
            }
            val reader = UcpSse(requireNotNull(stream))
            var messageEndpoint: String? = null
            while (messageEndpoint == null) {
                val event = reader.next() ?: error("Missing legacy MCP endpoint announcement")
                if (event.event == "endpoint") messageEndpoint = legacyEndpoint(endpoint, event.data)
                else if (event.event == "message") {
                    val value = UcpApi.parseObject(event.data)
                    checkEnvelope(value)
                    require(value["id"] == null) { "Server request before endpoint announcement" }
                }
            }
            val initializedRequest = initialize(LEGACY)
            send(wire, messageEndpoint, initializedRequest, baseHeaders)
            val initialized = awaitReply(reader, wire, messageEndpoint, requireNotNull(initializedRequest["id"]), baseHeaders)
            require(initialized["result"].asObject()?.text("protocolVersion") == LEGACY) { "Unsupported legacy MCP version" }
            send(wire, messageEndpoint, notification(), baseHeaders)
            val request = toolCall(tool, arguments, version)
            send(wire, messageEndpoint, request, baseHeaders)
            result = UcpApi.unwrapResult(awaitReply(reader, wire, messageEndpoint, requireNotNull(request["id"]), baseHeaders), version, tool)
        }
        return requireNotNull(result)
    }
}
