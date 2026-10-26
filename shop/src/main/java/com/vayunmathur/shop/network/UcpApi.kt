package com.vayunmathur.shop.network

import com.vayunmathur.shop.data.UcpError
import com.vayunmathur.shop.domain.MerchantUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Anonymous advertised-service dispatch. No tool request is automatically replayed. */
internal object UcpApi {
    const val AGENT_PROFILE_URL = "https://maproj.org/.well-known/agent-profile.json"
    const val VERSION = "2026-08-25"
    const val APRIL_VERSION = "2026-04-08"
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    internal var transport: UcpTransport = UcpNetworkTransport

    fun profileUrl(version: String): String {
        require(version == VERSION || version == APRIL_VERSION) { "Unsupported UCP version" }
        return "https://maproj.org/.well-known/ucp?version=$version"
    }

    suspend fun serviceCall(service: UcpService, tool: String, arguments: JsonObject): Result<JsonObject> = ioCall {
        catching {
            profileUrl(service.version)
            require(MerchantUrls.httpsUrl(service.endpoint) != null && java.net.URI(service.endpoint).fragment == null) {
                "Invalid merchant endpoint"
            }
            require(arguments.toString().toByteArray(Charsets.UTF_8).size <= UcpWire.MAX_BODY_BYTES) { "Request exceeds limit" }
            require(tool in setOf("search_catalog", "lookup_catalog", "get_product", "create_checkout", "get_checkout", "update_checkout")) {
                "Unsupported operation; completion and cancellation are disabled"
            }
            // Snapshot the injection seam for the entire session, including cleanup.
            val wire = transport
            withTimeout(UcpWire.CALL_TIMEOUT_MS) {
                when (service.transport) {
                    "rest" -> UcpRest.call(wire, service, tool, arguments)
                    "mcp" -> UcpMcp.call(wire, service.endpoint, tool, arguments, service.version)
                    else -> throw UcpHttpException(UcpError.Unsupported("Unsupported shopping transport"))
                }
            }
        }
    }

    suspend fun mcpCall(endpoint: String, tool: String, arguments: JsonObject, version: String = VERSION): Result<JsonObject> =
        serviceCall(UcpService(version, "mcp", endpoint, emptySet(), emptySet()), tool, arguments)

    internal fun parseObject(body: String): JsonObject =
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw UcpHttpException(UcpError.Malformed("Expected JSON object"))

    internal fun validateResult(data: JsonObject, version: String = VERSION, operation: String? = null): JsonObject {
        if (data["ucp"].asObject()?.text("status") == "error" || data.containsKey("error")) {
            throw UcpHttpException(UcpError.Rpc("Merchant returned a UCP error"))
        }
        val validationErrors = data["messages"].asArray()?.any { it.asObject()?.text("type") == "error" } == true
        // Business validation belongs in native review, not in catalog or protocol error handling.
        if (validationErrors && (operation !in checkoutOperations || !isCheckoutSession(data, version))) {
            throw UcpHttpException(UcpError.Rpc("Merchant returned a UCP error"))
        }
        if (data["ucp"].asObject()?.text("version") != version) {
            throw UcpHttpException(UcpError.Malformed("Unexpected UCP response version"))
        }
        return data
    }

    private val checkoutOperations = setOf("create_checkout", "get_checkout", "update_checkout")
    private val checkoutStatuses = setOf("incomplete", "requires_escalation", "ready_for_complete", "complete_in_progress", "completed", "canceled")
    private val totalTypes = setOf("subtotal", "items_discount", "discount", "fulfillment", "tax", "fee", "total")

    private fun string(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun integer(o: JsonObject, key: String, version: String): Long? {
        val value = (o[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull() ?: return null
        if (version == VERSION && (value < -9_007_199_254_740_991L || value > 9_007_199_254_740_991L)) return null
        return value
    }

    private fun isCheckoutSession(data: JsonObject, version: String): Boolean {
        if (version !in setOf(VERSION, APRIL_VERSION) || string(data, "id").isNullOrBlank() ||
            string(data, "status") !in checkoutStatuses || string(data, "currency")?.matches(Regex("^[A-Z]{3}$")) != true) return false
        val totals = data["totals"].asArray() ?: return false
        if (!validTotals(data, version) || totals.count { it.asObject()?.let { total -> string(total, "type") } == "subtotal" } != 1 ||
            totals.count { it.asObject()?.let { total -> string(total, "type") } == "total" } != 1) return false
        val lines = data["line_items"].asArray() ?: return false
        return lines.all { element ->
            val line = element.asObject() ?: return@all false
            val item = line["item"].asObject() ?: return@all false
            !string(line, "id").isNullOrBlank() && !string(item, "id").isNullOrBlank() &&
                string(item, "title") != null && integer(item, "price", version)?.let { it >= 0 } == true &&
                integer(line, "quantity", version)?.let { it >= 1 } == true && validTotals(line, version)
        }
    }

    private fun validTotals(o: JsonObject, version: String): Boolean = o["totals"].asArray()?.all { element ->
        val total = element.asObject() ?: return@all false
        val type = string(total, "type")?.takeIf { it.isNotBlank() } ?: return@all false
        if (integer(total, "amount", version) == null ||
            (total.containsKey("display_text") && string(total, "display_text") == null) ||
            (type !in totalTypes && string(total, "display_text") == null)) return@all false
        if (!total.containsKey("lines")) return@all true
        total["lines"].asArray()?.all subLineLoop@ { subElement ->
            val subLine = subElement.asObject() ?: return@subLineLoop false
            string(subLine, "display_text") != null && integer(subLine, "amount", version) != null
        } == true
    } == true

    internal fun unwrapResult(envelope: JsonObject, version: String = VERSION, operation: String? = null): JsonObject {
        envelope["error"]?.let { throw UcpHttpException(UcpError.Rpc("Merchant RPC rejected the request")) }
        val result = envelope["result"] as? JsonObject
            ?: throw UcpHttpException(UcpError.Malformed("Missing MCP result"))
        if (result["isError"].isTruthy() == true) {
            throw UcpHttpException(UcpError.Rpc("Merchant tool rejected the request"))
        }
        val data = result["structuredContent"] as? JsonObject
            ?: result["content"].asArray()?.firstNotNullOfOrNull { item ->
                val content = item.asObject() ?: return@firstNotNullOfOrNull null
                if (content.text("type") != "text") return@firstNotNullOfOrNull null
                content.text("text")?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            }
            ?: throw UcpHttpException(UcpError.Malformed("Missing structured tool content"))
        return validateResult(data, version, operation)
    }

    internal fun withAgentProfile(arguments: JsonObject, version: String = VERSION): JsonObject = buildJsonObject {
        arguments.forEach { (key, value) -> put(key, value) }
        putJsonObject("meta") {
            arguments["meta"].asObject()?.forEach { (key, value) -> put(key, value) }
            putJsonObject("ucp-agent") { put("profile", profileUrl(version)) }
        }
    }

    internal suspend fun <T> catching(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        Result.failure(UcpHttpException(UcpError.Transport("Merchant request timed out")))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    internal suspend fun <T> ioCall(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}

class UcpHttpException(val error: UcpError) : Exception(error.toString())
