package com.vayunmathur.shop.network

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UcpDiscoveryTest {
    private fun profile(version: String = "2026-08-25", transport: String = "mcp", endpoint: String = "https://gateway.example/custom-path"): JsonObject =
        UcpApi.json.parseToJsonElement("""{"ucp":{"version":"$version",
          "services":{"dev.ucp.shopping":[{"version":"$version","transport":"$transport","endpoint":"$endpoint"}]},
          "capabilities":{"dev.ucp.shopping.catalog.search":[{"version":"$version"}]},"payment_handlers":{}}}""") as JsonObject

    @Test
    fun usesAdvertisedEndpointInsteadOfGuessedShopifyPath() {
        assertEquals("https://gateway.example/custom-path", UcpDiscovery.parseEndpoint(profile(), "dev.ucp.shopping.catalog.search"))
    }

    @Test
    fun rejectsOlderVersionUnsupportedTransportAndUnsafeEndpoint() {
        listOf(profile(version = "2026-04-08"), profile(transport = "rest"), profile(endpoint = "http://gateway.example/mcp"),
            profile(endpoint = "https://127.0.0.1/mcp")).forEach {
            assertFailsWith<UcpHttpException> { UcpDiscovery.parseEndpoint(it, "dev.ucp.shopping.catalog.search") }
        }
    }

    @Test
    fun requiresAdvertisedCheckoutCapability() {
        assertFailsWith<UcpHttpException> { UcpDiscovery.parseEndpoint(profile(), "dev.ucp.shopping.checkout") }
    }
}
