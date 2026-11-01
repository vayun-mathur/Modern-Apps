package com.vayunmathur.shop.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What the host should do with one message from the merchant's Embedded Checkout. */
sealed interface EcAction {
    data class Reply(val json: String) : EcAction
    data class OpenWindow(val id: JsonElement, val url: String) : EcAction
    data class Completed(val orderId: String, val permalinkUrl: String?, val totalMinor: Long?) : EcAction
    data class Failed(val message: String) : EcAction
    data object Ignore : EcAction
}

/**
 * UCP Embedded Checkout Protocol host logic without payment delegation: the merchant page
 * handles payment; the host only completes the handshake, opens links and observes completion.
 * Buyer data in checkout payloads is never retained.
 */
object EmbeddedCheckout {
    const val VERSION = "2026-08-25"
    private const val METHOD_NOT_FOUND = -32601
    private val json = Json { ignoreUnknownKeys = true }

    fun embeddedUrl(continueUrl: String, dark: Boolean): String? {
        val base = MerchantUrls.httpsUrl(continueUrl) ?: return null
        val separator = if ('?' in base) '&' else '?'
        val scheme = if (dark) "dark" else "light"
        return "$base${separator}ec_version=$VERSION&ec_color_scheme=$scheme&ec_delegate=window.open"
    }

    fun handle(message: String): EcAction {
        val obj = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return EcAction.Ignore
        val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return EcAction.Ignore
        val id = obj["id"]
        val params = obj["params"] as? JsonObject
        return when (method) {
            "ec.ready" -> id?.let { EcAction.Reply(success(it)) } ?: EcAction.Ignore
            "ec.window.open_request" -> {
                val url = (params?.get("url") as? JsonPrimitive)?.contentOrNull?.let(MerchantUrls::httpsUrl)
                when {
                    id == null -> EcAction.Ignore
                    url == null -> EcAction.Reply(windowOpenResult(id, ok = false))
                    else -> EcAction.OpenWindow(id, url)
                }
            }
            "ec.complete" -> completed(params)
            "ec.error" -> EcAction.Failed(errorText(params))
            else -> if (id != null) EcAction.Reply(methodNotFound(id, method)) else EcAction.Ignore
        }
    }

    fun windowOpenResult(id: JsonElement, ok: Boolean): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", buildJsonObject {
            put("ucp", ucp(if (ok) "success" else "error"))
            if (!ok) {
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "error")
                        put("code", "window_open_rejected_error")
                        put("content", "Window open rejected by host")
                    })
                })
            }
        })
    }.toString()

    private fun success(id: JsonElement): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", buildJsonObject { put("ucp", ucp("success")) })
    }.toString()

    private fun methodNotFound(id: JsonElement, method: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", METHOD_NOT_FOUND)
            put("message", "Unsupported by host: $method")
        })
    }.toString()

    private fun ucp(status: String) = buildJsonObject {
        put("version", VERSION)
        put("status", status)
    }

    private fun completed(params: JsonObject?): EcAction {
        val checkout = params?.get("checkout") as? JsonObject ?: return EcAction.Ignore
        val order = checkout["order"] as? JsonObject
        val orderId = (order?.get("id") as? JsonPrimitive)?.contentOrNull
            ?: (checkout["id"] as? JsonPrimitive)?.contentOrNull
            ?: return EcAction.Ignore
        val permalink = (order?.get("permalink_url") as? JsonPrimitive)?.contentOrNull?.let(MerchantUrls::httpsUrl)
        return EcAction.Completed(orderId, permalink, totalMinor(checkout))
    }

    private fun totalMinor(checkout: JsonObject): Long? =
        (checkout["totals"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { (it["type"] as? JsonPrimitive)?.contentOrNull == "total" }
            ?.let { (it["amount"] as? JsonPrimitive)?.longOrNull }

    private fun errorText(params: JsonObject?): String {
        val error = params?.get("error") as? JsonObject
        return (error?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (error?.get("content") as? JsonPrimitive)?.contentOrNull
            ?: "The store's checkout reported an error."
    }
}
