package com.vayunmathur.shop.network

import com.vayunmathur.shop.data.CheckoutInputs
import com.vayunmathur.shop.data.UcpCheckout
import com.vayunmathur.shop.domain.NativeCheckoutFixtures as F
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class NativeCheckoutProjectionTest {
    private val inputs = CheckoutInputs(firstName = "Ephemeral", lastName = "Buyer", email = "ephemeral@example.com", phone = "+14155550123",
        street = "123 Test St", extendedAddress = "Unit 1", city = "Test City", region = "CA", country = "US", postalCode = "94043", options = mapOf("merchant-shipping/merchant-group" to "express"))

    @Test fun bothDatedUpdatesIncludeWritableBuyerAndMerchantIdsOnly() {
        for (version in listOf("2026-08-25", "2026-04-08")) {
            for (transport in listOf("rest", "mcp")) {
                val args = UcpCheckoutApi.mutationArguments(F.service(version, transport), F.lines, inputs, F.checkout(version), "stable-intent")
                assertEquals("checkout-1", args["id"]?.jsonPrimitive?.content)
                assertEquals("stable-intent", args["meta"]?.jsonObject?.get("idempotency_key")?.jsonPrimitive?.content)
                val checkout = args["checkout"]!!.jsonObject
                assertEquals(setOf("line_items", "buyer", "fulfillment"), checkout.keys)
                assertEquals("+14155550123", checkout["buyer"]?.jsonObject?.get("phone_number")?.jsonPrimitive?.content)
                val line = checkout["line_items"]!!.jsonArray.single().jsonObject
                assertEquals(setOf("id", "item", "quantity"), line.keys)
                assertEquals("merchant-line-1", line["id"]!!.jsonPrimitive.content)
                assertEquals(2, line["quantity"]!!.jsonPrimitive.int)
                assertEquals(version == "2026-08-25", "quantity_unit" in line["item"]!!.jsonObject)
                val method = checkout["fulfillment"]!!.jsonObject["methods"]!!.jsonArray.single().jsonObject
                assertEquals("merchant-shipping", method["id"]!!.jsonPrimitive.content)
                assertEquals(listOf("merchant-line-1"), method["line_item_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
                val destination = method["destinations"]!!.jsonArray.single().jsonObject
                assertEquals("merchant-address", destination["id"]!!.jsonPrimitive.content)
                assertEquals("123 Test St", destination["street_address"]!!.jsonPrimitive.content)
                assertEquals(version == "2026-08-25", "type" in destination)
                val group = method["groups"]!!.jsonArray.single().jsonObject
                assertEquals(setOf("id", "selected_option_id"), group.keys)
                assertEquals("merchant-group", group["id"]!!.jsonPrimitive.content)
                assertEquals("express", group["selected_option_id"]!!.jsonPrimitive.content)
                assertFalse("totals" in checkout)
                assertFalse("payment" in checkout)
                assertFalse("status" in checkout)
            }
        }
    }

    @Test fun createOmitsResponseIdsAndDeclaresOnlyEachForCurrentRelease() {
        for (version in listOf("2026-08-25", "2026-04-08")) {
            val args = UcpCheckoutApi.mutationArguments(F.service(version), F.lines, CheckoutInputs(email = "test@example.com"), null, "create-intent")
            assertFalse("id" in args)
            val line = args["checkout"]!!.jsonObject["line_items"]!!.jsonArray.single().jsonObject
            assertFalse("id" in line)
            if (version == "2026-08-25") assertEquals(buildJsonObject { put("unit", "C62"); put("scale", 0) }, line["item"]!!.jsonObject["quantity_unit"])
        }
    }

    @Test fun rejectsStaleLinesInventedChoicesFractionalBasisAndUnadvertisedFulfillment() {
        assertFails { UcpCheckoutApi.mutationArguments(F.service(), F.lines, inputs, F.checkout(itemId = "different"), "i") }
        assertFails { UcpCheckoutApi.mutationArguments(F.service(), F.lines, inputs.copy(options = mapOf("merchant-shipping/merchant-group" to "invented")), F.checkout(), "i") }
        val source = F.checkout().writableSource!!
        val line = source["line_items"]!!.jsonArray.single().jsonObject
        val weighted = JsonObject(line + ("item" to JsonObject(line["item"]!!.jsonObject + ("quantity_unit" to buildJsonObject { put("unit", "KGM"); put("scale", 3) }))))
        val checkout = F.checkout().copy(writableSource = JsonObject(source + ("line_items" to JsonArray(listOf(weighted)))))
        assertFails { UcpCheckoutApi.mutationArguments(F.service(), F.lines, inputs, checkout, "i") }
        assertFails { UcpCheckoutApi.mutationArguments(F.service().copy(capabilities = setOf("dev.ucp.shopping.checkout")), F.lines, inputs, F.checkout(), "i") }
        assertFails { UcpCheckoutApi.mutationArguments(F.service(), F.lines, inputs, F.checkout(methodType = "pickup"), "i") }
    }

    @Test fun advertisedShippingCanCreateMethodWithoutInventingAnyIds() {
        for (version in listOf("2026-08-25", "2026-04-08")) {
            val previous = F.checkout(version)
            val source = previous.writableSource!!
            val fulfillment = buildJsonObject {
                putJsonArray("available_methods") { add(buildJsonObject { put("type", "shipping"); putJsonArray("line_item_ids") { add(JsonPrimitive("merchant-line-1")) } }) }
            }
            val advertised = previous.copy(writableSource = JsonObject(source + ("fulfillment" to fulfillment)))
            val args = UcpCheckoutApi.mutationArguments(F.service(version), F.lines, inputs.copy(options = emptyMap(), shippingRequested = true), advertised, "shipping-intent")
            val method = args["checkout"]!!.jsonObject["fulfillment"]!!.jsonObject["methods"]!!.jsonArray.single().jsonObject
            assertFalse("id" in method)
            assertEquals("shipping", method["type"]!!.jsonPrimitive.content)
            assertEquals("merchant-line-1", method["line_item_ids"]!!.jsonArray.single().jsonPrimitive.content)
            val destination = method["destinations"]!!.jsonArray.single().jsonObject
            assertFalse("id" in destination)
            assertFalse("selected_destination_id" in method)
            assertEquals("123 Test St", destination["street_address"]!!.jsonPrimitive.content)
            assertFails { UcpCheckoutApi.mutationArguments(F.service(version), F.lines, inputs.copy(options = emptyMap(), shippingRequested = true), advertised.copy(writableSource = JsonObject(source + ("fulfillment" to buildJsonObject {}))), "i") }
        }
    }

    @Test fun sessionWithoutContinueUrlHasOrderedReviewAndNoPersistedBuyerSource() {
        val checkout = F.checkout()
        assertEquals("", checkout.continueUrl)
        assertEquals(listOf("subtotal", "fulfillment", "total"), checkout.totalEntries.map { it.type })
        assertEquals("KWD", checkout.totalEntries.last().money.currency)
        assertEquals("$.buyer.phone_number", checkout.messages.single().path)
        assertEquals("recoverable", checkout.messages.single().severity)
        assertEquals("Terms", checkout.review.links.single().title)
        assertEquals(listOf("standard", "express"), checkout.review.shipping.single().groups.single().options.map { it.id })
        assertNull(checkout.review.browserOnlyReason)
        val serialized = Json.encodeToString(checkout)
        assertFalse(serialized.contains("ephemeral@example.com"))
        assertFalse(serialized.contains("writableSource"))
        assertFalse(serialized.contains("street_address"))
        assertEquals(setOf("line_items", "fulfillment"), checkout.writableSource!!.keys)
        assertEquals("UcpCheckout(redacted)", checkout.toString())
        assertEquals(checkout.checkoutId, Json.decodeFromString<UcpCheckout>(serialized).checkoutId)
    }

    @Test fun nonNativeValidationSeverityAndUnknownRequiredFieldsAreBrowserOnly() {
        val source = F.response()
        for ((severity, path) in listOf("requires_buyer_input" to "$.buyer.email", "requires_buyer_review" to "$.buyer.email", "recoverable" to "$.buyer.tax_id")) {
            val messages = buildJsonArray { add(buildJsonObject { put("type", "error"); put("severity", severity); put("path", path); put("content", "Requires merchant review") }) }
            assertNotNull(UcpCheckoutApi.parseCheckout(JsonObject(source + ("messages" to messages)), capabilities = F.capabilities).review.browserOnlyReason)
        }
        val messages = buildJsonArray { add(buildJsonObject { put("type", "error"); put("severity", "recoverable"); put("path", "$.fulfillment.methods[0].destinations[0].postal_code"); put("content", "Check postal code") }) }
        assertNull(UcpCheckoutApi.parseCheckout(JsonObject(source + ("messages" to messages)), capabilities = F.capabilities).review.browserOnlyReason)
        val hints = buildJsonObject { putJsonArray("available_methods") { add(buildJsonObject { put("type", "shipping"); putJsonArray("line_item_ids") { add(JsonPrimitive("merchant-line-1")) } }) } }
        val checkout = UcpCheckoutApi.parseCheckout(JsonObject(source + ("fulfillment" to hints)), capabilities = F.capabilities)
        assertEquals(listOf("merchant-line-1"), checkout.review.availableShippingLineIds)
        assertNull(checkout.review.browserOnlyReason)
    }

    @Test fun unsupportedRequirementsStayBrowserOnlyAndInputsValidate() {
        assertNotNull(F.checkout(methodType = "drone").review.browserOnlyReason)
        assertNotNull(F.checkout(status = "requires_escalation").review.browserOnlyReason)
        val source = F.response()
        assertNotNull(UcpCheckoutApi.parseCheckout(JsonObject(source + ("actions" to buildJsonObject { put("identity_check", true) })), capabilities = F.capabilities).review.browserOnlyReason)
        assertFailsWith<UcpHttpException> { UcpCheckoutApi.parseCheckout(F.response("2026-04-08"), "2026-08-25", F.capabilities) }
        assertNotNull(inputs.copy(email = "bad").validationError())
        assertNotNull(inputs.copy(phone = "4155550123").validationError())
        assertNotNull(inputs.copy(country = "USA").validationError())
        assertEquals("CheckoutInputs(redacted)", inputs.toString())
    }
}
