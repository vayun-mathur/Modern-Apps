package com.vayunmathur.shop.domain

import com.vayunmathur.shop.data.CartLine
import com.vayunmathur.shop.data.PlacedOrder
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MerchantLinksTest {
    @Test
    fun productLinksYieldTheHandle() {
        assertEquals(MerchantLink.Product("www.allbirds.com", "tree-runner"),
            MerchantLinks.parse("https://www.allbirds.com/products/tree-runner?variant=123"))
        assertEquals(MerchantLink.Product("allbirds.com", "tree-runner"),
            MerchantLinks.parse("https://allbirds.com/collections/men/products/Tree-Runner/"))
    }

    @Test
    fun searchAndCollectionLinksBecomeScopedQueries() {
        assertEquals(MerchantLink.Search("allbirds.com", "wool socks"), MerchantLinks.parse("https://allbirds.com/search?q=wool+socks"))
        assertEquals(MerchantLink.Search("allbirds.com", "mens runners"), MerchantLinks.parse("https://allbirds.com/collections/mens-runners"))
        assertEquals(MerchantLink.Merchant("allbirds.com"), MerchantLinks.parse("https://allbirds.com/collections/all"))
        assertEquals(MerchantLink.Merchant("allbirds.com"), MerchantLinks.parse("http://allbirds.com/pages/about"))
    }

    @Test
    fun rejectsNonWebAndLocalLinks() {
        listOf("intent://allbirds.com/products/x", "https://localhost/products/x", "https://user@allbirds.com/", "not a url")
            .forEach { assertNull(MerchantLinks.parse(it), it) }
    }

    @Test
    fun legacyCartAndOrderJsonFallBackToDomain() {
        val json = Json { ignoreUnknownKeys = true }
        val line = json.decodeFromString<CartLine>("""{"variantId":"v","shopDomain":"allbirds.com"}""")
        assertEquals("", line.storeName)
        assertEquals("allbirds.com", line.displayName)
        assertEquals("Allbirds", line.copy(storeName = "Allbirds").displayName)
        assertEquals("allbirds.com", json.decodeFromString<PlacedOrder>("""{"checkoutId":"c","shopDomain":"allbirds.com"}""").displayName)
    }
}
