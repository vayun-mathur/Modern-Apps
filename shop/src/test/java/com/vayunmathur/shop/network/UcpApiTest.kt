package com.vayunmathur.shop.network

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UcpApiTest {
    private fun obj(raw: String) = UcpApi.json.parseToJsonElement(raw) as JsonObject

    @Test
    fun unwrapsStructuredContentAndTextFallback() {
        val content = obj("""{"ucp":{"version":"2026-08-25"},"products":[]}""")
        assertEquals(content, UcpApi.unwrapResult(obj("""{"result":{"structuredContent":$content}}""")))
        val encoded = UcpApi.json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(content.toString()))
        assertEquals(content, UcpApi.unwrapResult(obj("""{"result":{"content":[{"type":"text","text":$encoded}]}}""")))
    }

    @Test
    fun rejectsRpcMcpUcpAndVersionErrors() {
        listOf("""{"error":{"code":-32601}}""", """{"result":{"isError":true,"structuredContent":{"products":[]}}}""",
            """{"result":{"structuredContent":{"messages":[{"type":"error","code":"denied"}]}}}""",
            """{"result":{"structuredContent":{"ucp":{"status":"error","version":"2026-08-25"}}}}""",
            """{"result":{"structuredContent":{"ucp":{"version":"2026-04-08"},"products":[]}}}""").forEach {
            assertFailsWith<UcpHttpException> { UcpApi.unwrapResult(obj(it)) }
        }
    }

    @Test
    fun preservesIdempotencyMetadataWhileInjectingProfile() {
        val args = UcpApi.withAgentProfile(obj("""{"meta":{"idempotency-key":"request-1"},"id":"c1"}"""))
        assertEquals("\"request-1\"", (args["meta"] as JsonObject)["idempotency-key"].toString())
        assertEquals("\"c1\"", args["id"].toString())
    }

    @Test
    fun defaultAndNonCheckoutOperationsStillRejectSessionShapedErrors() {
        val content = validationSession(UcpApi.VERSION)
        val envelope = obj("""{"result":{"structuredContent":$content}}""")
        for (operation in listOf(null, "search_catalog", "lookup_catalog", "get_product", "complete_checkout", "cancel_checkout")) {
            assertFailsWith<UcpHttpException> { UcpApi.unwrapResult(envelope, UcpApi.VERSION, operation) }
        }
    }

    @Test
    fun explicitCheckoutOperationsPreserveStructuredAndTextValidationMessages() {
        for (version in listOf(UcpApi.VERSION, UcpApi.APRIL_VERSION)) {
            val content = validationSession(version)
            val structured = obj("""{"result":{"structuredContent":$content}}""")
            val encoded = UcpApi.json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(content.toString()))
            val text = obj("""{"result":{"content":[{"type":"text","text":$encoded}]}}""")
            for (operation in listOf("create_checkout", "get_checkout", "update_checkout")) {
                assertEquals(content, UcpApi.unwrapResult(structured, version, operation))
                assertEquals(content, UcpApi.unwrapResult(text, version, operation))
            }
        }
    }

    private fun validationSession(version: String) = obj("""{
        "ucp":{"version":"$version"},"id":"session-1","status":"requires_escalation","currency":"USD",
        "line_items":[],"totals":[{"type":"subtotal","amount":0},{"type":"total","amount":0}],
        "links":[],"messages":[{"type":"error","severity":"requires_buyer_input","code":"missing","content":"Buyer input required."}]
    }""")
}
