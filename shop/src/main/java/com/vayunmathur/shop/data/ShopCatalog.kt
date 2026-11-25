package com.vayunmathur.shop.data

import com.vayunmathur.shop.network.ShopProduct
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * Offline catalog published by the server to R2
 * (`https://static.maproj.org/shop/v1/catalog.json`, schema 1).
 *
 * Coverage is honest by construction: [merchants] is the complete
 * directory, [products] is a capped seed-query snapshot — UCP has no
 * browse/list endpoint, so stores without a sampled product are
 * search-only via the live API. Prices/availability are as of
 * [generatedAt] and are always revalidated at checkout (the merchant
 * session totals are authoritative; see [priceRevalidationNote]).
 */
@Serializable
data class ShopCatalogFile(
    val schema: Int = 0,
    @SerialName("generated_at") val generatedAt: String = "",
    val coverage: CatalogCoverage = CatalogCoverage(),
    val merchants: List<CatalogMerchantEntry> = emptyList(),
    val products: List<ShopProduct> = emptyList(),
)

/** Immutable value describing catalog coverage. */
@Serializable
data class CatalogCoverage(
    @SerialName("directory_merchants") val directoryMerchants: Int = 0,
    @SerialName("merchants_included") val merchantsIncluded: Int = 0,
    @SerialName("product_count") val productCount: Int = 0,
    @SerialName("stores_with_products") val storesWithProducts: Int = 0,
    @SerialName("search_only_stores") val searchOnlyStores: Int = 0,
    val note: String = "",
)

/** Immutable value describing catalog merchant entry. */
@Serializable
data class CatalogMerchantEntry(
    val name: String = "",
    val domain: String = "",
    val description: String = "",
)

/** Pure offline-catalog logic: local search, lookup, freshness, price checks. */
object ShopCatalog {
    const val SCHEMA = 1

    /** Catalogs older than this are shown as stale; checkout never trusts them either way. */
    const val STALE_AFTER_MS = 26 * 60 * 60 * 1000L

    fun generatedAtMs(catalog: ShopCatalogFile): Long =
        runCatching { Instant.parse(catalog.generatedAt).toEpochMilli() }.getOrDefault(0L)

    fun isStale(catalog: ShopCatalogFile, nowMs: Long): Boolean =
        generatedAtMs(catalog).let { it == 0L || nowMs - it > STALE_AFTER_MS }

    /** Accept only the schema this build understands; anything else stays live-only. */
    fun isUsable(catalog: ShopCatalogFile): Boolean = catalog.schema == SCHEMA

    /**
     * Offline search over the snapshot: every query token must appear in
     * title/description/store/domain. Scoped when [shop] is set. This is
     * a substring sample search, not the server's ranked full search.
     */
    fun search(catalog: ShopCatalogFile, query: String, shop: String? = null, limit: Int = 40): List<ShopProduct> {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty() || !isUsable(catalog)) return emptyList()
        return catalog.products.filter { product ->
            (shop.isNullOrBlank() || product.shopDomain.equals(shop, ignoreCase = true)) &&
                tokens.all { token ->
                    product.title.lowercase().contains(token) ||
                        product.description.lowercase().contains(token) ||
                        product.storeName.lowercase().contains(token) ||
                        product.shopDomain.lowercase().contains(token)
                }
        }.take(limit.coerceIn(1, 50))
    }

    /** Offline merchant browse: token match over name/domain/description. */
    fun searchMerchants(catalog: ShopCatalogFile, query: String, limit: Int = 40): List<CatalogMerchantEntry> {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (!isUsable(catalog)) return emptyList()
        if (tokens.isEmpty()) return catalog.merchants.take(limit.coerceIn(1, 50))
        return catalog.merchants.filter { merchant ->
            tokens.all { token ->
                merchant.name.lowercase().contains(token) ||
                    merchant.domain.lowercase().contains(token) ||
                    merchant.description.lowercase().contains(token)
            }
        }.take(limit.coerceIn(1, 50))
    }

    fun productByVariant(catalog: ShopCatalogFile, variantId: String): ShopProduct? =
        catalog.products.firstOrNull { product -> product.variants.any { it.id == variantId } }

    /**
     * Honest checkout UX: when the live merchant subtotal differs from
     * the cart's snapshot (catalog or earlier search) prices, say so.
     * Returns null when they agree (per currency). The live total the
     * merchant charges is never overridden by the snapshot.
     */
    fun priceRevalidationNote(lines: List<CartLine>, liveSubtotal: Money): String? {
        if (lines.isEmpty()) return null
        if (lines.any { it.unitPrice.currency != liveSubtotal.currency }) {
            return "Prices were confirmed with the store at checkout and may differ from the prices shown earlier."
        }
        val snapshot = lines.sumOf { it.unitPrice.amount * it.quantity }
        return if (snapshot != liveSubtotal.amount) {
            "The store updated its prices at checkout. The checkout total is the price you will pay."
        } else {
            null
        }
    }
}
