package com.vayunmathur.shop.network

import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.CheckoutReview
import com.vayunmathur.shop.data.CheckoutLegalLink
import com.vayunmathur.shop.data.CheckoutShippingMethod
import com.vayunmathur.shop.data.CheckoutShippingGroup
import com.vayunmathur.shop.data.CheckoutShippingOption
import com.vayunmathur.shop.domain.CheckoutProjection
import com.vayunmathur.shop.domain.MerchantUrls
import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.Money
import com.vayunmathur.shop.data.UcpCheckout
import com.vayunmathur.shop.data.UcpError
import com.vayunmathur.shop.data.UcpMessage
import com.vayunmathur.shop.data.UcpTotalEntry
import com.vayunmathur.shop.data.UcpTotals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Checkout session preparation only; payment/completion needs an authorized payment integration. */
object UcpCheckoutApi {
    suspend fun createCheckout(platform: UcpPlatform, shopDomain: String, lines: List<CartLine>): Result<UcpCheckout> {
        if (lines.isEmpty() || lines.any { it.shopDomain != shopDomain || it.platformId != platform.id || it.quantity !in 1..99 }) {
            return Result.failure(UcpHttpException(UcpError.Malformed("Invalid checkout lines")))
        }
        val service = UcpDiscovery.resolve(shopDomain, "dev.ucp.shopping.checkout")
            .getOrElse { return Result.failure(it) }
        return mutate(service, lines, CheckoutInputs(), null, java.util.UUID.randomUUID().toString())
    }

    suspend fun getCheckout(shopDomain: String, checkoutId: String): Result<UcpCheckout> {
        val service = UcpDiscovery.resolve(shopDomain, "dev.ucp.shopping.checkout")
            .getOrElse { return Result.failure(it) }
        return getCheckout(service, checkoutId)
    }

    internal suspend fun getCheckout(service: UcpService, checkoutId: String): Result<UcpCheckout> =
        UcpApi.serviceCall(service, "get_checkout", buildJsonObject { put("id", checkoutId) })
            .mapCatching { parseCheckout(it, service.version, service.capabilities) }

    internal suspend fun mutate(service: UcpService, lines: List<CartLine>, inputs: CheckoutInputs, previous: UcpCheckout?, idempotencyKey: String): Result<UcpCheckout> {
        val arguments = runCatching { mutationArguments(service, lines, inputs, previous, idempotencyKey) }
            .getOrElse { return Result.failure(UcpHttpException(UcpError.Unsupported("These checkout requirements need the merchant website."))) }
        return UcpApi.serviceCall(service, if (previous == null) "create_checkout" else "update_checkout", arguments)
            .mapCatching { parseCheckout(it, service.version, service.capabilities) }
    }

    internal fun mutationArguments(service: UcpService, lines: List<CartLine>, inputs: CheckoutInputs, previous: UcpCheckout?, idempotencyKey: String): JsonObject = buildJsonObject {
        previous?.let { put("id", it.checkoutId) }
        put("checkout", CheckoutProjection.checkout(service.version, lines, inputs, previous, "dev.ucp.shopping.fulfillment" in service.capabilities))
        putJsonObject("meta") { put("idempotency_key", idempotencyKey) }
    }

    internal fun parseCheckout(result: JsonObject, version: String = "2026-08-25", capabilities: Set<String> = emptySet()): UcpCheckout {
        val responseVersion = result["ucp"].asObject()?.text("version")
        if (version !in CheckoutProjection.versions || (responseVersion != null && responseVersion != version)) {
            throw UcpHttpException(UcpError.Malformed("Checkout version mismatch"))
        }
        val id = result.text("id")?.takeIf { it.isNotBlank() }
            ?: throw UcpHttpException(UcpError.Malformed("Missing checkout ID"))
        val status = result.text("status") ?: throw UcpHttpException(UcpError.Malformed("Missing checkout status"))
        if (status !in setOf("incomplete", "requires_escalation", "ready_for_complete", "complete_in_progress", "completed", "canceled")) {
            throw UcpHttpException(UcpError.Malformed("Unknown checkout status"))
        }
        val currency = result.text("currency") ?: throw UcpHttpException(UcpError.Malformed("Missing currency"))
        val entries = result["totals"].asArray()?.map { item ->
            val total = item.asObject() ?: throw UcpHttpException(UcpError.Malformed("Invalid total"))
            val type = total.text("type") ?: throw UcpHttpException(UcpError.Malformed("Missing total type"))
            UcpTotalEntry(type, total.text("display_text") ?: type, Money(
                total.money("amount") ?: throw UcpHttpException(UcpError.Malformed("Missing total amount")), currency,
            ))
        } ?: throw UcpHttpException(UcpError.Malformed("Missing totals"))
        fun required(type: String): Money = entries.singleOrNull { it.type == type }?.money
            ?: throw UcpHttpException(UcpError.Malformed("Expected exactly one $type"))
        return UcpCheckout(
            checkoutId = id,
            status = status,
            currency = currency,
            totals = UcpTotals(subtotal = required("subtotal"), total = required("total")),
            totalEntries = entries,
            continueUrl = result.text("continue_url").orEmpty(),
            expiresAt = result.text("expires_at"),
            messages = result["messages"].asArray()?.mapNotNull { it.asObject()?.let { message ->
                UcpMessage(message.text("type").orEmpty(), message.text("content").orEmpty(), message.text("path"), message.text("code"), message.text("severity"))
            } }.orEmpty(),
            version = version,
            review = parseReview(result, currency, capabilities),
            writableSource = JsonObject(result.filterKeys { it == "line_items" || it == "fulfillment" }),
        )
    }

    private fun parseReview(result: JsonObject, currency: String, capabilities: Set<String>): CheckoutReview {
        val methods = result["fulfillment"].asObject()?.get("methods").asArray().orEmpty()
        val supported = "dev.ucp.shopping.fulfillment" in capabilities
        val available = result["fulfillment"].asObject()?.get("available_methods").asArray().orEmpty()
        val availableShipping = if (!supported) emptyList() else available.filter { it.asObject()?.text("type") == "shipping" }
            .flatMap { it.asObject()?.get("line_item_ids").asArray().orEmpty().mapNotNull { id -> (id as? kotlinx.serialization.json.JsonPrimitive)?.content } }.distinct()
        var reason: String? = null
        if (methods.isEmpty() && available.isNotEmpty() && availableShipping.isEmpty()) reason = "Available fulfillment choices require the merchant website."
        val negotiated = result["ucp"].asObject()?.get("capabilities").asObject()?.keys.orEmpty()
        if (negotiated.any { it !in setOf("dev.ucp.shopping.checkout", "dev.ucp.shopping.fulfillment") }) reason = "This checkout uses additional extensions that require the merchant website."
        if (methods.isNotEmpty() && !supported) reason = "The merchant's fulfillment extension is not supported."
        if (methods.any { it.asObject()?.text("type") != "shipping" }) reason = "This fulfillment method requires the merchant website."
        if (methods.any { it.asObject()?.text("id").isNullOrBlank() || it.asObject()?.get("line_item_ids").asArray().isNullOrEmpty() }) reason = "The merchant's fulfillment identifiers are missing. Use its website."
        if (result["actions"].asArray()?.isNotEmpty() == true || result["actions"].asObject()?.isNotEmpty() == true || result["policies"].asArray()?.isNotEmpty() == true) reason = "Additional merchant requirements must be reviewed on its website."
        val buyerPaths = setOf("first_name", "last_name", "email", "phone_number").map { "$.buyer.$it" }.toSet()
        val addressPath = Regex("^\\$\\.fulfillment\\.methods\\[[0-9]+]\\.destinations\\[[0-9]+]\\.(first_name|last_name|phone_number|street_address|extended_address|address_locality|address_region|address_country|postal_code)$")
        val optionPath = Regex("^\\$\\.fulfillment\\.methods\\[[0-9]+]\\.groups\\[[0-9]+]\\.selected_option_id$")
        if (result["messages"].asArray().orEmpty().any { element ->
            val message = element.asObject()
            val path = message?.text("path").orEmpty()
            message?.text("type") == "error" && (message.text("severity") != "recoverable" || (path !in buyerPaths && !addressPath.matches(path) && !optionPath.matches(path)))
        }) reason = "The merchant requires information or approval that must be provided on its website."
        if (result.text("status") in setOf("requires_escalation", "complete_in_progress", "completed", "canceled")) reason = "Continue on the merchant website. Shop cannot complete or confirm purchases."
        val shipping = if (!supported) emptyList() else methods.mapNotNull { element ->
            val method = element.asObject() ?: return@mapNotNull null
            if (method.text("type") != "shipping") return@mapNotNull null
            val methodId = method.text("id") ?: return@mapNotNull null
            CheckoutShippingMethod(methodId, method["groups"].asArray().orEmpty().mapNotNull groupLoop@ { groupElement ->
                val group = groupElement.asObject() ?: return@groupLoop null
                val groupId = group.text("id") ?: return@groupLoop null
                CheckoutShippingGroup(groupId, group.text("selected_option_id"), group["options"].asArray().orEmpty().mapNotNull optionLoop@ { optionElement ->
                    val option = optionElement.asObject() ?: return@optionLoop null
                    val optionId = option.text("id") ?: return@optionLoop null
                    CheckoutShippingOption(optionId, option.text("title") ?: optionId,
                        option["description"].asObject()?.text("plain") ?: option.text("description").orEmpty(),
                        option["totals"].asArray().orEmpty().mapNotNull { totalElement ->
                            val total = totalElement.asObject() ?: return@mapNotNull null
                            val amount = total.money("amount") ?: return@mapNotNull null
                            UcpTotalEntry(total.text("type").orEmpty(), total.text("display_text") ?: total.text("type").orEmpty(), Money(amount, currency))
                        })
                })
            })
        }
        val links = result["links"].asArray().orEmpty().mapNotNull { element ->
            val link = element.asObject() ?: return@mapNotNull null
            val url = link.text("url")?.let(MerchantUrls::httpsUrl) ?: return@mapNotNull null
            CheckoutLegalLink(link.text("type").orEmpty(), link.text("title") ?: link.text("type").orEmpty(), url)
        }
        if (result["links"].asArray()?.size != links.size) reason = reason ?: "Legal links are missing or unsupported; review terms on the merchant website."
        return CheckoutReview(shipping, links, reason, availableShipping)
    }

    fun splitByShop(lines: List<CartLine>): Map<Pair<String, String>, List<CartLine>> =
        lines.groupBy { Pair(it.platformId, it.shopDomain) }

    fun platformFor(platformId: String): UcpPlatform =
        UcpPlatforms.ALL.firstOrNull { it.id == platformId } ?: UcpPlatforms.GENERIC
}
