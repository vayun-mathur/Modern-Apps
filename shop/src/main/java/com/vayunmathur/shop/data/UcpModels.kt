package com.vayunmathur.shop.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

/** App projections of UCP responses, not full protocol schemas. Money uses ISO minor units. */
@Serializable
data class Money(val amount: Long = 0, val currency: String = "USD")

@Serializable
data class ProductImage(val url: String = "", val alt: String? = null)

@Serializable
data class CatalogOptionSelection(val name: String, val label: String, val id: String? = null)

@Serializable
data class ProductVariant(
    val id: String = "",
    val title: String = "",
    val price: Money = Money(),
    val available: Boolean = false,
    val optionValues: List<String> = emptyList(),
    val sku: String? = null,
    val storeName: String = "",
    val shopDomain: String = "",
    val selectedOptions: List<CatalogOptionSelection> = emptyList(),
)

@Serializable
data class UcpProduct(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val priceRange: Money = Money(),
    val url: String = "",
    val platformId: String = "shopify",
    val shopDomain: String = "",
    val images: List<ProductImage> = emptyList(),
    val variants: List<ProductVariant> = emptyList(),
    val checkoutEligible: Boolean = false,
    val descriptionHtml: String = "",
    val storeName: String = "",
    val priceRangeMax: Money? = null,
)

@Serializable
data class CartLine(
    val variantId: String = "",
    val productTitle: String = "",
    val variantTitle: String = "",
    val unitPrice: Money = Money(),
    val imageUrl: String = "",
    val platformId: String = "shopify",
    val shopDomain: String = "",
    val quantity: Int = 1,
)

@Serializable
data class UcpTotals(
    val subtotal: Money = Money(),
    val shipping: Money? = null,
    val tax: Money? = null,
    val total: Money = Money(),
)

@Serializable
data class UcpTotalEntry(val type: String, val label: String, val money: Money)

@Serializable
data class UcpMessage(val type: String = "", val text: String = "", val path: String? = null, val code: String? = null, val severity: String? = null)

@Serializable
data class UcpCheckout(
    val checkoutId: String = "",
    val status: String = "",
    val currency: String = "USD",
    val totals: UcpTotals = UcpTotals(),
    val totalEntries: List<UcpTotalEntry> = emptyList(),
    val expiresAt: String? = null,
    val continueUrl: String = "",
    val messages: List<UcpMessage> = emptyList(),
    val version: String = "2026-08-25",
    @Transient val review: CheckoutReview = CheckoutReview(),
    // Session-only source for exact writable projections. Never serialize buyer-bearing responses.
    @Transient internal val writableSource: JsonObject? = null,
) {
    override fun toString(): String = "UcpCheckout(redacted)"
}

/** Legacy storage name retained for compatibility. Handoffs are pending, not placed orders. */
@Serializable
data class PlacedOrder(
    val checkoutId: String = "",
    val placedAtMs: Long = 0,
    val total: Money = Money(),
    val itemCount: Int = 0,
    val continueUrl: String = "",
    val status: String = "pending",
    val platformId: String = "generic",
    val shopDomain: String = "",
    val lines: List<CartLine> = emptyList(),
    val totalEntries: List<UcpTotalEntry> = emptyList(),
    val expiresAt: String? = null,
    val version: String = "2026-08-25",
)

sealed interface UcpError {
    data class Transport(val reason: String) : UcpError
    data class RateLimited(val retryAfterMs: Long?) : UcpError
    data class Http(val status: Int, val snippet: String) : UcpError
    data class Malformed(val reason: String) : UcpError
    data class Rpc(val message: String) : UcpError
    data class Unsupported(val reason: String) : UcpError
}

fun UcpError.message(): String = when (this) {
    is UcpError.Transport -> "Couldn't reach the store. Check your connection and try again."
    is UcpError.RateLimited -> "The store is busy. Please wait a moment and try again."
    is UcpError.Http -> "The store returned an error ($status). Please try again."
    is UcpError.Malformed -> "The store sent an unexpected response. Please try again."
    is UcpError.Unsupported -> reason
    is UcpError.Rpc -> "The merchant couldn't process the request."
}
