package com.vayunmathur.shop.network

import com.vayunmathur.library.network.NetworkDataStream
import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.library.network.SimpleResponse
import com.vayunmathur.library.network.asNetworkDataStream
import com.vayunmathur.shop.data.Money
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Offline fixtures projected from each official dated catalog/product/variant schema. */
class UcpCatalogDatedTest {
    private val current = UcpApi.VERSION
    private val april = UcpApi.APRIL_VERSION
    private val rootUrl = "https://shop.example/.well-known/ucp"
    private val endpoint = "https://gateway.example/advertised-api"

    private fun obj(raw: String) = UcpApi.json.parseToJsonElement(raw) as JsonObject
    private fun product(amount: String = "12999", extraVariant: String = ""): String = """
        {"id":"p1","title":"Trail Runner","description":{"plain":"Grippy shoes"},
          "url":"https://shop.example/products/trail",
          "price_range":{"min":{"amount":$amount,"currency":"USD"},"max":{"amount":$amount,"currency":"USD"}},
          "media":[{"type":"image","url":"https://shop.example/image.jpg","alt_text":"Side"},
            {"type":"image","url":"https://tracker.example/image.jpg"},
            {"type":"video","url":"https://shop.example/video.mp4"}],
          "variants":[{"id":"v1","title":"9 / Blue","description":{"plain":"Blue"},
            "price":{"amount":$amount,"currency":"USD"},"availability":{"available":true},
            "options":[{"name":"Size","label":"9"},{"name":"Color","label":"Blue"}],
            "inputs":[{"id":"v1","match":"exact"}]$extraVariant}]}
    """
    private fun response(version: String, item: String = product()): JsonObject =
        obj("""{"ucp":{"version":"$version"},"products":[$item]}""")

    private fun manifest(version: String): String = """{"ucp":{"version":"$version",
        "services":{"dev.ucp.shopping":[{"version":"$version","transport":"rest","endpoint":"$endpoint"}]},
        "capabilities":{"dev.ucp.shopping.catalog.search":[{"version":"$version"}],
          "dev.ucp.shopping.catalog.lookup":[{"version":"$version"}]},"payment_handlers":{}}}"""

    private class CatalogTransport(
        val manifest: String, val payload: JsonObject,
    ) : UcpTransport {
        val requests = mutableListOf<UcpRequest>()
        var streamsConsumed = 0
        override suspend fun execute(request: UcpRequest): RawResponse {
            requests.add(request)
            return RawResponse(200, "", manifest.toByteArray(Charsets.UTF_8), emptyMap(), request.url)
        }
        override suspend fun stream(request: UcpRequest, block: suspend (NetworkDataStream?, SimpleResponse) -> Unit): SimpleResponse {
            requests.add(request)
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            val stream = bytes.asNetworkDataStream()
            val response = SimpleResponse(200, "", "", mapOf("Content-Type" to listOf("application/json")), request.url)
            block(stream, response)
            if (stream.isClosedForRead) streamsConsumed++
            return response
        }
    }

    private fun withTransport(fake: CatalogTransport, block: suspend () -> Unit) = runBlocking {
        val previous = UcpApi.transport
        try {
            UcpApi.transport = fake
            block()
        } finally {
            UcpApi.transport = previous
        }
    }

    @Test
    fun searchDispatchesBothDatedRestServicesWithExactRequestProjection() {
        for (version in listOf(current, april)) {
            val fake = CatalogTransport(manifest(version), response(version))
            withTransport(fake) {
                val products = UcpCatalog.searchStore(UcpPlatforms.GENERIC, "shop.example", "shoes", 100).getOrThrow()
                val item = products.single()
                assertEquals("p1", item.id)
                assertEquals("Grippy shoes", item.description)
                assertEquals(Money(12999, "USD"), item.priceRange)
                assertEquals("shop.example", item.shopDomain)
                assertEquals(listOf("9", "Blue"), item.variants.single().optionValues)
                assertTrue(item.variants.single().available)
                assertEquals(listOf("https://shop.example/image.jpg"), item.images.map { it.url })
                assertEquals(listOf(rootUrl, "$endpoint/catalog/search"), fake.requests.map { it.url })
                val call = fake.requests.last()
                assertEquals("POST", call.method)
                assertEquals(obj("""{"query":"shoes","context":{"address_country":"US"},"pagination":{"limit":50}}"""), obj(call.body!!))
                assertEquals("profile=\"${UcpApi.profileUrl(version)}\"", call.headers["UCP-Agent"])
                assertTrue(fake.requests.all { !it.followRedirects })
                assertEquals(1, fake.streamsConsumed)
            }
        }
    }

    @Test
    fun lookupAndSingleProductUseTheirExactDatedOperations() {
        for (version in listOf(current, april)) {
            val lookup = CatalogTransport(manifest(version), response(version))
            withTransport(lookup) {
                assertEquals("v1", UcpCatalog.lookup(UcpPlatforms.GENERIC, "shop.example", listOf("v1")).getOrThrow()
                    .single().variants.single().id)
                val call = lookup.requests.last()
                assertEquals("$endpoint/catalog/lookup", call.url)
                assertEquals(obj("""{"ids":["v1"]}"""), obj(call.body!!))
            }
            val detail = CatalogTransport(manifest(version), obj("""{"ucp":{"version":"$version"},"product":${product()}}"""))
            withTransport(detail) {
                assertEquals("p1", UcpCatalog.getProduct(UcpPlatforms.GENERIC, "shop.example", "p1").getOrThrow().id)
                val call = detail.requests.last()
                assertEquals("$endpoint/catalog/product", call.url)
                assertEquals(obj("""{"id":"p1"}"""), obj(call.body!!))
            }
        }
    }

    @Test
    fun emptyLookupDoesNotDiscoverOrCallMerchant() {
        val fake = CatalogTransport(manifest(current), response(current))
        withTransport(fake) {
            assertTrue(UcpCatalog.lookup(UcpPlatforms.GENERIC, "shop.example", emptyList()).getOrThrow().isEmpty())
            assertTrue(fake.requests.isEmpty())
        }
    }

    @Test
    fun expectedResponseVersionMustMatchSelectedLeaf() {
        for ((selected, received) in listOf(current to april, april to current)) {
            val fake = CatalogTransport(manifest(selected), response(received))
            withTransport(fake) {
                assertTrue(UcpCatalog.searchStore(UcpPlatforms.GENERIC, "shop.example", "shoe").isFailure)
                assertEquals(2, fake.requests.size)
            }
            assertFailsWith<UcpHttpException> {
                UcpCatalog.parseProducts(response(received), "generic", "shop.example", selected)
            }
        }
        assertFailsWith<UcpHttpException> {
            UcpCatalog.parseProducts(obj("""{"products":[]}"""), "generic", "shop.example", current)
        }
    }

    @Test
    fun augustUsesEffectiveQuantityDefaultsAndDisablesUnsupportedOrderingGranularity() {
        for (descriptor in listOf("", """, "quantity_unit":{"unit":"C62","display_text":"each"}""",
            """, "quantity_unit":{"unit":"C62","display_text":"each","scale":0,"increment":1}""")) {
            val item = UcpCatalog.parseProducts(response(current, product(extraVariant = descriptor)), "generic", "shop.example", current).single()
            assertTrue(item.variants.single().available)
        }
        for (descriptor in listOf(
            """, "quantity_unit":{"unit":"KGM","display_text":"kg","scale":2}""",
            """, "quantity_unit":{"unit":"C62","display_text":"each","increment":6}""",
            """, "quantity_unit":{"unit":"C62","display_text":"each","scale":"0"}""",
            """, "quantity_unit":null""",
        )) {
            val item = UcpCatalog.parseProducts(response(current, product(extraVariant = descriptor)), "generic", "shop.example", current).single()
            assertFalse(item.variants.single().available)
        }
    }

    @Test
    fun augustAmountCeilingIsNotMergedIntoAprilProjection() {
        val amount = "9007199254740992"
        assertFailsWith<UcpHttpException> {
            UcpCatalog.parseProducts(response(current, product(amount)), "generic", "shop.example", current)
        }
        val item = UcpCatalog.parseProducts(response(april, product(amount)), "generic", "shop.example", april).single()
        assertEquals(Money(9007199254740992L, "USD"), item.priceRange)
        assertEquals(item.priceRange, item.variants.single().price)
    }

    @Test
    fun rejectsNonIntegerAmountsAndLegacyShapesInBothDatedProjections() {
        for (version in listOf(current, april)) {
            for (amount in listOf("1.25", "\"100\"", "-1", "9223372036854775808")) {
                assertFailsWith<UcpHttpException> {
                    UcpCatalog.parseProducts(response(version, product(amount)), "generic", "shop.example", version)
                }
            }
            for (broken in listOf(
                product().replace("\"description\":{\"plain\":\"Grippy shoes\"}", "\"description\":\"legacy\""),
                product().replace("\"currency\":\"USD\"", "\"currency\":\"usd\""),
                product().replace("\"max\":{\"amount\":12999,\"currency\":\"USD\"}", "\"max\":{}"),
                """{"id":"p1","title":"Legacy","variants":[]}""",
            )) {
                assertFailsWith<UcpHttpException> {
                    UcpCatalog.parseProducts(response(version, broken), "generic", "shop.example", version)
                }
            }
        }
    }

    @Test
    fun emptyResultsSucceedAndMerchantErrorsOrMissingCollectionsFail() {
        for (version in listOf(current, april)) {
            assertTrue(UcpCatalog.parseProducts(obj("""{"ucp":{"version":"$version"},"products":[]}"""),
                "generic", "shop.example", version).isEmpty())
            for (raw in listOf(
                """{"ucp":{"version":"$version"}}""",
                """{"ucp":{"version":"$version","status":"error"},"products":[]}""",
                """{"ucp":{"version":"$version"},"messages":[{"type":"error"}],"products":[]}""",
            )) {
                assertFailsWith<UcpHttpException> {
                    UcpCatalog.parseProducts(obj(raw), "generic", "shop.example", version)
                }
            }
        }
    }
}
