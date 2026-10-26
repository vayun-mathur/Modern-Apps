package com.vayunmathur.shop.domain

import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.UcpCheckout
import kotlinx.serialization.json.*

/** Explicit projections verified against the official dated checkout/fulfillment schemas. */
internal object CheckoutProjection {
    val versions = setOf("2026-08-25", "2026-04-08")

    fun checkout(version: String, lines: List<CartLine>, inputs: CheckoutInputs, previous: UcpCheckout?, fulfillment: Boolean): JsonObject {
        require(version in versions)
        require(inputs.validationError() == null)
        require(lines.isNotEmpty() && lines.all { it.quantity in 1..99 })
        val sourceLines = previous?.writableSource?.get("line_items") as? JsonArray
        if (previous != null) require(sourceLines != null) { "Missing authoritative line items" }
        val projectedLines = if (previous == null) lines.map { line -> buildJsonObject {
            putJsonObject("item") {
                put("id", line.variantId)
                if (version == "2026-08-25") putJsonObject("quantity_unit") { put("unit", "C62"); put("scale", 0) }
            }
            put("quantity", line.quantity)
        } } else {
            require(sourceLines!!.size == lines.size) { "Checkout line items changed" }
            require(sourceLines.map { it.jsonObject["item"]!!.jsonObject["id"]!!.jsonPrimitive.content }.sorted() == lines.map { it.variantId }.sorted()) { "Checkout item identities changed" }
            val authoritativeIds = sourceLines.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            require(authoritativeIds.all { it.isNotBlank() } && authoritativeIds.distinct().size == authoritativeIds.size) { "Invalid merchant line IDs" }
            sourceLines.map { element ->
                val remote = element.jsonObject
                val item = remote["item"]!!.jsonObject
                val id = item["id"]!!.jsonPrimitive.content
                val local = lines.single { it.variantId == id }
                require(remote["quantity"]?.jsonPrimitive?.intOrNull == local.quantity) { "Checkout quantity changed" }
                val unit = item["quantity_unit"] as? JsonObject
                require(unit == null || (unit["unit"]?.jsonPrimitive?.content == "C62" && unit["scale"]?.jsonPrimitive?.intOrNull == 0)) { "Unsupported quantity basis" }
                buildJsonObject {
                    put("id", remote["id"] ?: error("Missing merchant line ID"))
                    putJsonObject("item") {
                        put("id", id)
                        if (version == "2026-08-25") putJsonObject("quantity_unit") { put("unit", "C62"); put("scale", 0) }
                    }
                    put("quantity", local.quantity)
                    remote["parent_id"]?.let { put("parent_id", it) }
                }
            }
        }
        return buildJsonObject {
            put("line_items", JsonArray(projectedLines))
            putJsonObject("buyer") {
                if (previous != null || inputs.firstName.isNotBlank()) put("first_name", inputs.firstName)
                if (previous != null || inputs.lastName.isNotBlank()) put("last_name", inputs.lastName)
                if (previous != null || inputs.email.isNotBlank()) put("email", inputs.email)
                if (previous != null || inputs.phone.isNotBlank()) put("phone_number", inputs.phone)
            }
            if (fulfillment && (inputs.hasAddress || inputs.options.isNotEmpty())) {
                val responseFulfillment = previous?.writableSource?.get("fulfillment") as? JsonObject
                val responseMethods = responseFulfillment?.get("methods") as? JsonArray
                val methods = if (!responseMethods.isNullOrEmpty()) responseMethods else {
                    require(previous != null && inputs.shippingRequested && inputs.hasAddress) { "First create a session to obtain merchant shipping choices" }
                    val hints = (responseFulfillment?.get("available_methods") as? JsonArray).orEmpty().map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "shipping" }
                    val ids = hints.flatMap { it["line_item_ids"]!!.jsonArray }.distinct()
                    require(ids.isNotEmpty()) { "Merchant did not advertise shipping" }
                    // Update permits creating a method without an ID. Merchant assigns it; hints supply exact line IDs.
                    JsonArray(listOf(buildJsonObject { put("type", "shipping"); put("line_item_ids", JsonArray(ids)) }))
                }
                val knownKeys = methods.flatMap { method ->
                    val methodId = method.jsonObject["id"]?.jsonPrimitive?.content
                    (method.jsonObject["groups"] as? JsonArray).orEmpty().map { "$methodId/${it.jsonObject["id"]!!.jsonPrimitive.content}" }
                }.toSet()
                require(inputs.options.keys.all { it in knownKeys }) { "Unknown shipping group" }
                putJsonObject("fulfillment") {
                    put("methods", JsonArray(methods.map { element ->
                        val method = element.jsonObject
                        require(method["type"]?.jsonPrimitive?.content == "shipping") { "Unsupported fulfillment" }
                        val methodId = method["id"]?.jsonPrimitive?.content
                        require(methodId == null && responseMethods.isNullOrEmpty() || !methodId.isNullOrBlank())
                        if (version == "2026-08-25") require((method["destinations"] as? JsonArray).orEmpty().all { it.jsonObject["type"]?.jsonPrimitive?.content == "shipping_address" }) { "Unsupported shipping destination" }
                        val methodLines = method["line_item_ids"]!!.jsonArray.map { it.jsonPrimitive.content }
                        val checkoutIds = sourceLines!!.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
                        require(methodLines.isNotEmpty() && methodLines.all { it in checkoutIds }) { "Stale fulfillment lines" }
                        buildJsonObject {
                            methodId?.let { put("id", it) }
                            put("type", "shipping")
                            put("line_item_ids", method["line_item_ids"] ?: error("Missing fulfillment lines"))
                            if (inputs.hasAddress) {
                                val destinations = method["destinations"] as? JsonArray
                                val selectedId = (method["selected_destination_id"] as? JsonPrimitive)?.contentOrNull
                                val destination = destinations?.map { it.jsonObject }?.firstOrNull { it["id"]?.jsonPrimitive?.content == selectedId }
                                    ?: destinations?.firstOrNull()?.jsonObject
                                val destinationId = destination?.get("id")
                                putJsonArray("destinations") { add(buildJsonObject {
                                    destinationId?.let { put("id", it) }
                                    if (version == "2026-08-25") put("type", "shipping_address")
                                    put("first_name", inputs.firstName); put("last_name", inputs.lastName); put("phone_number", inputs.phone)
                                    put("street_address", inputs.street); put("extended_address", inputs.extendedAddress)
                                    put("address_locality", inputs.city); put("address_region", inputs.region)
                                    put("address_country", inputs.country); put("postal_code", inputs.postalCode)
                                }) }
                                // An authored address can omit its ID; only select IDs actually issued by the merchant.
                                destinationId?.let { put("selected_destination_id", it) }
                            } else method["selected_destination_id"]?.let { put("selected_destination_id", it) }
                            val groups = method["groups"] as? JsonArray ?: JsonArray(emptyList())
                            put("groups", JsonArray(groups.map { groupElement ->
                                val group = groupElement.jsonObject
                                val groupId = group["id"]!!.jsonPrimitive.content
                                require(groupId.isNotBlank())
                                require(group["line_item_ids"]!!.jsonArray.all { it.jsonPrimitive.content in methodLines }) { "Stale fulfillment group" }
                                val selected = inputs.options["$methodId/$groupId"]
                                if (selected != null) require((group["options"] as? JsonArray)?.any { it.jsonObject["id"]?.jsonPrimitive?.content == selected } == true) { "Unknown shipping option" }
                                buildJsonObject {
                                    put("id", groupId)
                                    if (selected != null) put("selected_option_id", selected)
                                    else group["selected_option_id"]?.let { put("selected_option_id", it) }
                                }
                            }))
                        }
                    }))
                }
            } else require(!inputs.hasAddress && inputs.options.isEmpty()) { "Merchant shipping must be supported before submitting an address" }
        }
    }
}
