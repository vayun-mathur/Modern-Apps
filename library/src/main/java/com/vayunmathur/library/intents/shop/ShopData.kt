package com.vayunmathur.library.intents.shop

import kotlinx.serialization.Serializable

/**
 * Shared wire DTOs for shop's cross-app assistant intents
 * ([com.vayunmathur.library.util.AssistantIntent]): `SearchProductsIntent`,
 * `GetProductIntent`, `GetCartIntent`, `AddToCartIntent`,
 * `RemoveFromCartIntent` and `GetOrdersIntent` in
 * `com.vayunmathur.shop.intents`, answered only for a co-signed caller behind
 * `com.vayunmathur.shop.permissions.ACCESS_SHOP`.
 *
 * Prepare-only by design (see shop/AGENTS.md): these DTOs can search the
 * catalog, read the local cart and order summaries, and add/remove local cart
 * lines. They cannot create, update or complete a checkout, hold an
 * idempotency key, or read the buyer profile; checkout stays a one-tap user
 * action in the shop app (the assistant hands off via the `shop://cart` deep
 * link), and a cart/browser handoff is never reported as a paid order.
 *
 * Every field has a default so older/newer callers can omit fields without
 * breaking decode (library DTO-compat rule). Prices are ISO minor units
 * (e.g. cents) in [currency], exactly as the shop backend reports them.
 */

/** Search the catalog for [query] (blank returns nothing provider-side). */
@Serializable
data class SearchProductsQuery(
    val query: String = "",
    val limit: Int = 10,
)

/**
 * One purchasable variant of a product: a search hit expands to one row per
 * variant so [variantId] can be handed verbatim to [AddToCartRequest].
 *
 * @property productId catalog product id; pass it back to [GetProductQuery].
 * @property price variant price in minor units of [currency].
 */
@Serializable
data class ProductData(
    val title: String = "",
    val productId: String = "",
    val variantId: String = "",
    val shopDomain: String = "",
    val storeName: String = "",
    val price: Long = 0L,
    val currency: String = "USD",
    val imageUrl: String = "",
    val checkoutEligible: Boolean = false,
)

/** Read one product; [variantId] optionally names the variant to price. */
@Serializable
data class GetProductQuery(
    val shopDomain: String = "",
    val productId: String = "",
    val variantId: String? = null,
)

/** One variant of a [ProductDetailData]. */
@Serializable
data class ProductVariantData(
    val variantId: String = "",
    val title: String = "",
    val price: Long = 0L,
    val currency: String = "USD",
    val available: Boolean = false,
    val sku: String? = null,
)

/**
 * Product detail plus every variant. [price] is the requested variant's
 * price when [GetProductQuery.variantId] matched, else the product's
 * starting price. [error] is a stable machine-readable code when the lookup
 * failed (`INVALID_REQUEST`, `BACKEND_FAILED`); never thrown.
 */
@Serializable
data class ProductDetailData(
    val title: String = "",
    val productId: String = "",
    val shopDomain: String = "",
    val storeName: String = "",
    val description: String = "",
    val price: Long = 0L,
    val currency: String = "USD",
    val imageUrl: String = "",
    val checkoutEligible: Boolean = false,
    val variants: List<ProductVariantData> = emptyList(),
    val error: String? = null,
)

/** One local cart line. [price] is the unit price in minor units. */
@Serializable
data class CartLineData(
    val title: String = "",
    val variantTitle: String = "",
    val variantId: String = "",
    val shopDomain: String = "",
    val storeName: String = "",
    val price: Long = 0L,
    val currency: String = "USD",
    val quantity: Int = 1,
)

/** The cart lines of one store; checkout is per-store in the shop app. */
@Serializable
data class CartStoreData(
    val shopDomain: String = "",
    val storeName: String = "",
    val lines: List<CartLineData> = emptyList(),
)

/**
 * The whole local cart, grouped by store. [error] is a stable
 * machine-readable code when a cart write was refused
 * (`INVALID_REQUEST`, `INVALID_QUANTITY`, `VARIANT_NOT_FOUND`,
 * `VARIANT_UNAVAILABLE`, `NOT_CHECKOUT_ELIGIBLE`, `BACKEND_FAILED`); the
 * cart is left unchanged in that case.
 */
@Serializable
data class CartData(
    val stores: List<CartStoreData> = emptyList(),
    val totalItems: Int = 0,
    val error: String? = null,
)

/**
 * Add [quantity] (integer, 1..99) of [variantId] from [shopDomain] to the
 * local cart. The provider re-fetches the product and confirms that exact
 * variant exists and is available before writing; it never creates a
 * checkout and never auto-retries an uncertain result.
 */
@Serializable
data class AddToCartRequest(
    val shopDomain: String = "",
    val variantId: String = "",
    val quantity: Int = 1,
)

/** Remove every local cart line with [variantId]. Local-only, no network. */
@Serializable
data class RemoveFromCartRequest(
    val variantId: String = "",
)

/**
 * One placed-order / pending-handoff summary. [status] is the latest local
 * tracking status when one is known, else the stored record status
 * (`pending` for a browser handoff — never proof of payment — or
 * `completed` once the merchant's `ec.complete` signal arrived).
 *
 * @property total order total in minor units of [currency].
 */
@Serializable
data class OrderData(
    val orderId: String = "",
    val checkoutId: String = "",
    val shopDomain: String = "",
    val storeName: String = "",
    val status: String = "",
    val total: Long = 0L,
    val currency: String = "USD",
    val itemCount: Int = 0,
    val placedAtMs: Long = 0L,
)
