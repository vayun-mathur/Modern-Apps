package com.vayunmathur.shop.network

import com.vayunmathur.library.network.NetworkDataStream
import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.library.network.SimpleResponse
import com.vayunmathur.library.network.asNetworkDataStream
import com.vayunmathur.shop.data.UcpError
import com.vayunmathur.shop.data.CatalogOptionSelection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UcpProductDetailTest {
    private fun obj(raw: String) = UcpApi.json.parseToJsonElement(raw) as JsonObject
    private fun product(seller: String = "", url: String = "https://shop.example/products/p1"): String = """
        {"id":"p1","title":"Trail Runner","description":{"html":"<p>Soft <strong>wool</strong>.</p>"},
         "url":"$url","price_range":{"min":{"amount":100,"currency":"USD"},"max":{"amount":100,"currency":"USD"}},
         "variants":[{"id":"v1","title":"Blue","description":{"plain":"Blue"},
           "price":{"amount":100,"currency":"USD"},"availability":{"available":true}$seller}]}
    """

    private class DetailTransport(
        private val version: String, product: String, private val searchProduct: String? = null,
        private val detailStatus: Int = 200,
    ) : UcpTransport {
        val requests = mutableListOf<UcpRequest>()
        private val manifest = """{"ucp":{"version":"$version",
            "services":{"dev.ucp.shopping":[{"version":"$version","transport":"rest","endpoint":"https://shop.example/api"}]},
            "capabilities":{"dev.ucp.shopping.catalog.lookup":[{"version":"$version"}],
                "dev.ucp.shopping.catalog.search":[{"version":"$version"}]},"payment_handlers":{}}}"""
        private val payload = """{"ucp":{"version":"$version"},"product":$product}"""
        override suspend fun execute(request: UcpRequest): RawResponse {
            requests.add(request)
            return RawResponse(200, "", manifest.toByteArray(), emptyMap(), request.url)
        }
        override suspend fun stream(
            request: UcpRequest, block: suspend (NetworkDataStream?, SimpleResponse) -> Unit,
        ): SimpleResponse {
            requests.add(request)
            val search = request.url.endsWith("/catalog/search")
            val body = if (search) """{"ucp":{"version":"$version"},"products":[${searchProduct ?: ""}]}""" else payload
            val response = SimpleResponse(if (search) 200 else detailStatus, "", "", mapOf("Content-Type" to listOf("application/json")), request.url)
            block(body.toByteArray().asNetworkDataStream(), response)
            return response
        }
    }

    @Test
    fun refreshUsesProductDetailAndConfirmsTheRequestedVariantForBothVersions() = runBlocking {
        val previous = UcpApi.transport
        try {
            for (version in listOf(UcpApi.VERSION, UcpApi.APRIL_VERSION)) {
                val fake = DetailTransport(version, product())
                UcpApi.transport = fake
                val refreshed = UcpCatalog.refreshProduct(UcpPlatforms.GENERIC, "shop.example", "v1", "p1").getOrThrow()
                assertEquals("v1", refreshed.variants.single().id)
                assertEquals("<p>Soft <strong>wool</strong>.</p>", refreshed.descriptionHtml)
                assertEquals("https://shop.example/api/catalog/product", fake.requests.last().url)
                assertEquals(obj("""{"id":"p1","context":{"address_country":"US"}}"""), obj(fake.requests.last().body!!))
                assertEquals(2, fake.requests.size)
            }
        } finally {
            UcpApi.transport = previous
        }
    }

    @Test
    fun detailRequestSendsOptionNamesLabelsAndOptionalValueIds() = runBlocking {
        val previous = UcpApi.transport
        try {
            for (version in listOf(UcpApi.VERSION, UcpApi.APRIL_VERSION)) {
                val fake = DetailTransport(version, product())
                UcpApi.transport = fake
                val options = listOf(CatalogOptionSelection("Size", "9", "size-9"), CatalogOptionSelection("Color", "Blue"))
                UcpCatalog.refreshProduct(UcpPlatforms.GENERIC, "shop.example", "v1", "p1", options).getOrThrow()
                assertEquals(obj("""{"id":"p1","context":{"address_country":"US"},
                    "selected":[{"name":"Size","label":"9","id":"size-9"},{"name":"Color","label":"Blue"}]}"""), obj(fake.requests.last().body!!))
            }
        } finally {
            UcpApi.transport = previous
        }
    }

    @Test
    fun parserRetainsSelectionsNeededToRefreshTheTappedVariant() {
        for (version in listOf(UcpApi.VERSION, UcpApi.APRIL_VERSION)) {
            val item = product().replace("\"availability\":", "\"options\":[{\"name\":\"Size\",\"label\":\"9\",\"id\":\"size-9\"}],\"availability\":")
            val data = obj("""{"ucp":{"version":"$version"},"products":[$item]}""")
            val parsed = UcpCatalog.parseProducts(data, "generic", "shop.example", version).single()
            assertEquals(listOf(CatalogOptionSelection("Size", "9", "size-9")), parsed.variants.single().selectedOptions)
        }
    }

    @Test
    fun featuredDetailOptionUsesFreshMerchantSearchToConfirmTheExactSku() = runBlocking {
        val previous = UcpApi.transport
        try {
            for (version in listOf(UcpApi.VERSION, UcpApi.APRIL_VERSION)) {
                val fake = DetailTransport(version, product().replace("\"v1\"", "\"featured\""), product())
                UcpApi.transport = fake
                val item = UcpCatalog.refreshProduct(UcpPlatforms.GENERIC, "shop.example", "v1", "p1", productTitle = "Trail Runner").getOrThrow()
                assertEquals("v1", item.variants.first().id)
                assertEquals(setOf("v1", "featured"), item.variants.map { it.id }.toSet())
                assertEquals("https://shop.example/api/catalog/search", fake.requests.last().url)
                assertEquals(4, fake.requests.size)
            }
        } finally {
            UcpApi.transport = previous
        }
    }

    @Test
    fun deniedAndRateLimitedDetailCallsNeverTriggerAnotherRequest() = runBlocking {
        val previous = UcpApi.transport
        try {
            for (status in listOf(401, 403, 429)) {
                val fake = DetailTransport(UcpApi.VERSION, product(), product(), status)
                UcpApi.transport = fake
                assertTrue(UcpCatalog.refreshProduct(UcpPlatforms.GENERIC, "shop.example", "v1", "p1", productTitle = "Trail Runner").isFailure)
                assertEquals(2, fake.requests.size)
            }
        } finally {
            UcpApi.transport = previous
        }
    }

    @Test
    fun missingSelectedVariantFailsInsteadOfSubstitutingAnotherOption() = runBlocking {
        val previous = UcpApi.transport
        try {
            UcpApi.transport = DetailTransport(UcpApi.VERSION, product())
            val result = UcpCatalog.refreshProduct(UcpPlatforms.GENERIC, "shop.example", "removed", "p1")
            assertTrue(result.isFailure)
            assertTrue((result.exceptionOrNull() as? UcpHttpException)?.error is UcpError.Unsupported)
        } finally {
            UcpApi.transport = previous
        }
    }

    @Test
    fun deepLinksWithoutSearchSnapshotCanRequestTheVariantDirectly() = runBlocking {
        val previous = UcpApi.transport
        try {
            val fake = DetailTransport(UcpApi.VERSION, product())
            UcpApi.transport = fake
            UcpCatalog.refreshProduct(UcpPlatforms.GENERIC, "shop.example", "v1").getOrThrow()
            assertEquals("v1", obj(fake.requests.last().body!!).text("id"))
        } finally {
            UcpApi.transport = previous
        }
    }

    @Test
    fun globalCatalogRoutesToTheSellerNotTheShopAppProductPage() {
        for (version in listOf(UcpApi.VERSION, UcpApi.APRIL_VERSION)) {
            val seller = """, "seller":{"name":"Trail Supply","domain":"shop.example","url":"https://shop.example"}"""
            val response = obj("""{"ucp":{"version":"$version"},"products":[${product(seller, "https://shop.app/products/p1")}] }""")
            val parsed = UcpCatalog.parseProducts(response, "shopify", "", version).single()
            assertEquals("shop.example", parsed.shopDomain)
            assertEquals("Trail Supply", parsed.storeName)
            assertEquals("shop.example", parsed.variants.single().shopDomain)
            assertEquals("Trail Supply", parsed.variants.single().storeName)
            assertTrue(parsed.checkoutEligible)
        }
    }

    @Test
    fun sellerUrlSupportsIdentityWithoutADomainAndUnsafeSellersStayIneligible() {
        val seller = """, "seller":{"name":"Trail Supply","url":"https://shop.example"}"""
        val valid = obj("""{"ucp":{"version":"${UcpApi.VERSION}"},"products":[${product(seller, "https://shop.app/products/p1")}] }""")
        assertEquals("shop.example", UcpCatalog.parseProducts(valid, "shopify", "", UcpApi.VERSION).single().shopDomain)
        val unsafe = """, "seller":{"name":"Not trusted","domain":"127.0.0.1","url":"http://127.0.0.1"}"""
        val invalid = obj("""{"ucp":{"version":"${UcpApi.VERSION}"},"products":[${product(unsafe, "https://shop.app/products/p1")}] }""")
        val parsed = UcpCatalog.parseProducts(invalid, "shopify", "", UcpApi.VERSION).single()
        assertEquals("", parsed.shopDomain)
        assertFalse(parsed.checkoutEligible)
    }
}
