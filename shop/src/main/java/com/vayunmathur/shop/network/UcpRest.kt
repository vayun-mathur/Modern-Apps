package com.vayunmathur.shop.network

import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.util.UUID

/** Operation paths and direct request bodies from both dated shopping OpenAPI releases. */
internal object UcpRest {
    internal fun request(service: UcpService, tool: String, arguments: JsonObject): UcpRequest {
        val id = arguments.text("id")
        val path = when (tool) {
            "search_catalog" -> "catalog/search"
            "lookup_catalog" -> "catalog/lookup"
            "get_product" -> "catalog/product"
            "create_checkout" -> "checkout-sessions"
            "get_checkout", "update_checkout" -> {
                require(!id.isNullOrBlank()) { "Missing checkout ID" }
                // Encode one opaque path segment, not a URI or additional operation path.
                val segment = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20").replace(".", "%2E")
                "checkout-sessions/$segment"
            }
            else -> error("Unsupported REST operation")
        }
        val method = when (tool) { "get_checkout" -> "GET"; "update_checkout" -> "PUT"; else -> "POST" }
        val body = when (tool) {
            "get_checkout" -> null
            "create_checkout", "update_checkout" -> requireNotNull(arguments["checkout"].asObject()) { "Missing checkout body" }
            else -> requireNotNull(arguments["catalog"].asObject()) { "Missing catalog body" }
        }
        val endpoint = URI(service.endpoint)
        require(endpoint.rawQuery == null && endpoint.rawFragment == null) { "Invalid REST base endpoint" }
        val headers = mutableMapOf(
            "UCP-Agent" to "profile=\"${UcpApi.profileUrl(service.version)}\"",
            "Request-Id" to UUID.randomUUID().toString(),
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        )
        val meta = arguments["meta"].asObject()
        val key = meta?.text("idempotency_key") ?: meta?.text("idempotency-key")
        require(tool !in setOf("create_checkout", "update_checkout") || !key.isNullOrBlank()) {
            "Checkout writes require a caller-provided idempotency key"
        }
        if (key != null) {
            require(key.isNotBlank() && key.length <= 256) { "Invalid idempotency key" }
            headers["Idempotency-Key"] = key
        }
        return UcpRequest(service.endpoint.trimEnd('/') + "/" + path, method, headers, body?.toString())
            .also(UcpWire::checkRequest)
    }

    suspend fun call(wire: UcpTransport, service: UcpService, tool: String, arguments: JsonObject): JsonObject {
        var result: JsonObject? = null
        wire.stream(request(service, tool, arguments)) { stream, response ->
            UcpWire.checkStatus(response.status)
            require(response.header("Content-Type")?.substringBefore(';')?.trim()?.equals("application/json", true) == true) {
                "Expected JSON REST response"
            }
            requireNotNull(stream) { "Missing REST response" }
            result = UcpApi.validateResult(UcpApi.parseObject(UcpWire.readBody(stream)), service.version, tool)
        }
        return requireNotNull(result)
    }
}
