package com.vayunmathur.shop.data

import com.vayunmathur.shop.network.CatalogStore
import com.vayunmathur.shop.network.ShopMoney
import com.vayunmathur.shop.network.ShopProduct
import com.vayunmathur.shop.network.ShopVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShopCatalogTest {
    private fun product(id: String, title: String, domain: String, variant: String) = ShopProduct(
        id = id,
        title = title,
        description = "A $title for everyday use",
        shopDomain = domain,
        storeName = "Store $domain",
        variants = listOf(ShopVariant(id = variant, title = "Default", price = ShopMoney(1_000, "USD"), available = true)),
    )

    private fun catalog(products: List<ShopProduct>) = ShopCatalogFile(
        schema = 1,
        generatedAt = GENERATED_AT,
        merchants = listOf(
            CatalogMerchantEntry(name = "Shoe Shop", domain = "shoes.example", description = "Footwear"),
            CatalogMerchantEntry(name = "Tea House", domain = "tea.example", description = "Loose leaf tea"),
        ),
        products = products,
    )

    @Test
    fun localSearchMatchesTokensAndScope() {
        val c = catalog(
            listOf(
                product("1", "Red Shoes", "shoes.example", "v1"),
                product("2", "Green Tea", "tea.example", "v2"),
            ),
        )
        assertEquals(listOf("1"), ShopCatalog.search(c, "red shoes").map { it.id })
        assertEquals(listOf("2"), ShopCatalog.search(c, "tea", shop = "tea.example").map { it.id })
        assertTrue(ShopCatalog.search(c, "tea", shop = "shoes.example").isEmpty())
        assertTrue(ShopCatalog.search(c, "  ").isEmpty())
    }

    @Test
    fun merchantBrowseAndVariantLookup() {
        val c = catalog(listOf(product("1", "Red Shoes", "shoes.example", "v1")))
        assertEquals("tea.example", ShopCatalog.searchMerchants(c, "loose leaf").single().domain)
        assertEquals(2, ShopCatalog.searchMerchants(c, "").size)
        assertEquals("1", ShopCatalog.productByVariant(c, "v1")?.id)
        assertNull(ShopCatalog.productByVariant(c, "missing"))
    }

    @Test
    fun schemaGateAndFreshness() {
        val c = catalog(emptyList())
        assertTrue(ShopCatalog.isUsable(c))
        assertFalse(ShopCatalog.isUsable(c.copy(schema = 99)))
        assertFalse(ShopCatalog.isStale(c, GENERATED_MS + 60_000))
        assertTrue(ShopCatalog.isStale(c, GENERATED_MS + ShopCatalog.STALE_AFTER_MS + 1))
        assertTrue(ShopCatalog.isStale(c.copy(generatedAt = "junk"), GENERATED_MS))
    }

    @Test
    fun priceRevalidationOnlyWhenLiveDiffers() {
        val lines = listOf(
            CartLine(variantId = "v1", unitPrice = Money(1_000, "USD"), quantity = 2, shopDomain = "a.example"),
        )
        assertNull(ShopCatalog.priceRevalidationNote(lines, Money(2_000, "USD")))
        assertTrue(ShopCatalog.priceRevalidationNote(lines, Money(2_500, "USD"))!!.contains("updated"))
        assertTrue(ShopCatalog.priceRevalidationNote(lines, Money(2_000, "EUR"))!!.contains("confirmed"))
        assertNull(ShopCatalog.priceRevalidationNote(emptyList(), Money(0, "USD")))
    }

    @Test
    fun parseAndReplaceRules() {
        val body = """
            {"schema":1,"generated_at":"$GENERATED_AT","coverage":{"product_count":0},
             "merchants":[{"name":"A","domain":"a.example","description":""}],
             "products":[]}
        """.trimIndent()
        val parsed = CatalogStore.parse(body)
        assertEquals("a.example", parsed?.merchants?.single()?.domain)
        assertNull(CatalogStore.parse("""{"schema":99}"""))
        assertNull(CatalogStore.parse("not json"))
        val newer = parsed!!.copy(generatedAt = "2026-11-26T00:00:00Z")
        assertTrue(CatalogStore.shouldReplace(parsed, newer))
        assertFalse(CatalogStore.shouldReplace(newer, parsed))
        assertTrue(CatalogStore.shouldReplace(null, parsed))
    }

    private companion object {
        const val GENERATED_AT = "2026-11-25T00:00:00Z"
        // 2026-11-25T00:00:00Z
        const val GENERATED_MS = 1_795_564_800_000L
    }
}
