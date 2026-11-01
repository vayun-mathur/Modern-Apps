package com.vayunmathur.shop.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MerchantUrlsTest {
    @Test
    fun normalizesPublicMerchantDomain() {
        assertEquals("store.example", MerchantUrls.domain(" https://STORE.example/ "))
        assertEquals("store.example", MerchantUrls.domain("store.example"))
    }

    @Test
    fun rejectsAppSchemesCredentialsLocalHostsPortsAndPaths() {
        listOf("http://store.example", "intent://store.example", "https://user:pass@store.example",
            "https://127.0.0.1", "https://[::1]", "https://router.local", "https://store.example:8443",
            "https://store.example/cart").forEach { assertNull(MerchantUrls.domain(it), it) }
    }

    @Test
    fun shopifyCartPermalinkNeedsShopifyVariantIds() {
        assertEquals("https://store.example/cart/11:2,12:1",
            MerchantUrls.shopifyCartUrl("store.example", listOf("gid://shopify/ProductVariant/11" to 2, "gid://shopify/ProductVariant/12" to 1)))
        assertNull(MerchantUrls.shopifyCartUrl("store.example", listOf("gid://shopify/ProductVariant/11" to 1, "sku-12" to 1)))
        assertNull(MerchantUrls.shopifyCartUrl("localhost", listOf("gid://shopify/ProductVariant/11" to 1)))
        assertNull(MerchantUrls.shopifyCartUrl("store.example", emptyList()))
    }
}
