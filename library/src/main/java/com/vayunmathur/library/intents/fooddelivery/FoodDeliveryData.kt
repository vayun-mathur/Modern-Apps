package com.vayunmathur.library.intents.fooddelivery

import kotlinx.serialization.Serializable

/**
 * Shared wire DTOs for fooddelivery's cross-app assistant intents
 * ([com.vayunmathur.library.util.AssistantIntent]): `FindRestaurantsIntent`,
 * `GetMenuIntent`, `GetCartIntent`, `SetCartIntent`, `PrepareOrderIntent` and
 * `GetOrdersIntent` in `com.vayunmathur.fooddelivery.intents`, answered only for
 * a co-signed caller behind
 * `com.vayunmathur.fooddelivery.permissions.ACCESS_ORDERS`.
 *
 * Thin and UI-free by design; all money is integer cents. Every field has a
 * default so older/newer callers can omit fields without breaking decode
 * (library DTO-compat rule).
 *
 * The assistant never creates an order: there is deliberately no checkout /
 * place-order DTO here. [PrepareOrder] only prices the provider-owned cart;
 * the user places the order in the fooddelivery app itself, opened via the
 * `fooddelivery://checkout` deep link ([OrderSummaryData.checkoutDeepLink]).
 */

/** Find restaurants near ([lat], [lng]); [query] optionally filters name/tags. */
@Serializable
data class FindRestaurants(
    val query: String? = null,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val limit: Int = 5,
)

/** One restaurant as the assistant sees it. */
@Serializable
data class RestaurantData(
    val id: Int = 0,
    val name: String = "",
    val address: String = "",
    val isOpen: Boolean = false,
    val isDeliveryEnabled: Boolean = false,
    val isPickupEnabled: Boolean = false,
    val rating: Double? = null,
    val totalRatings: Int? = null,
    val distance: Double? = null,
    val tags: List<String> = emptyList(),
    val imageUrl: String = "",
)

/** Read the menu of merchant [merchantId]. */
@Serializable
data class GetMenu(
    val merchantId: Int = 0,
)

/** One modifier option on a menu item, priced in cents. */
@Serializable
data class MenuModifierData(
    val id: Int = 0,
    val name: String = "",
    val priceCents: Int = 0,
)

/**
 * Summary of one modifier group: enough for the assistant to pick valid
 * modifier ids and for the provider to re-validate required/min/max rules.
 */
@Serializable
data class MenuModifierGroupData(
    val id: Int = 0,
    val name: String = "",
    val required: Boolean = false,
    val minSelections: Int = 0,
    val maxSelections: Int = 0,
    val modifiers: List<MenuModifierData> = emptyList(),
)

/** One menu item; [available] is false when unavailable or out of stock. */
@Serializable
data class MenuItemData(
    val id: Int = 0,
    val name: String = "",
    val priceCents: Int = 0,
    val available: Boolean = false,
    val category: String = "",
    val modifierGroups: List<MenuModifierGroupData> = emptyList(),
)

/** One menu category, grouping items by [itemIds]. */
@Serializable
data class MenuCategoryData(
    val id: Int = 0,
    val name: String = "",
    val itemIds: List<Int> = emptyList(),
)

/** A merchant's menu. [error] is a stable code when the menu could not load. */
@Serializable
data class MenuData(
    val merchantId: Int = 0,
    val merchantName: String = "",
    val categories: List<MenuCategoryData> = emptyList(),
    val items: List<MenuItemData> = emptyList(),
    val error: String? = null,
)

/** Read the provider-owned cart. No fields; present for wire symmetry. */
@Serializable
data class GetCart(
    val unused: Boolean = false,
)

/** One cart line as the assistant sees it. [lineTotalCents] includes modifiers. */
@Serializable
data class CartItemData(
    val itemId: Int = 0,
    val name: String = "",
    val qty: Int = 0,
    val modifierIds: List<Int> = emptyList(),
    val lineTotalCents: Int = 0,
)

/**
 * The provider-owned cart (single merchant). [error] is a stable
 * machine-readable code when a preceding [SetCart] was rejected; a rejected
 * write leaves the previous cart untouched.
 */
@Serializable
data class CartData(
    val merchantId: Int = 0,
    val merchantName: String = "",
    val items: List<CartItemData> = emptyList(),
    val totalCents: Int = 0,
    val error: String? = null,
)

/** One line the assistant wants in the cart: menu [itemId], [qty], [modifierIds]. */
@Serializable
data class SetCartItem(
    val itemId: Int = 0,
    val qty: Int = 0,
    val modifierIds: List<Int> = emptyList(),
)

/**
 * Replace the provider cart atomically with [items] from [merchantId].
 *
 * The cart holds a single merchant: writing items for a different merchant
 * replaces the previous cart wholesale (mirroring the in-app "start a new
 * cart" rule). An empty [items] clears the cart. Every line is re-validated
 * provider-side against the live menu (item exists and is available,
 * modifier ids belong to the item, required/min/max selection rules,
 * quantity bounds) before anything is written; on any failure nothing is
 * written and the returned [CartData] carries the error code.
 */
@Serializable
data class SetCart(
    val merchantId: Int = 0,
    val items: List<SetCartItem> = emptyList(),
)

/**
 * Price the current cart for merchant [merchantId] without creating an order.
 * [addressId] refers to a provider-saved address by id (contents never cross
 * the wire); [tipCents] and [promo] are echoed into the summary.
 */
@Serializable
data class PrepareOrder(
    val merchantId: Int = 0,
    val isPickup: Boolean = false,
    val addressId: String? = null,
    val tipCents: Int = 0,
    val promo: String? = null,
)

/**
 * Totals for the current cart, computed provider-side WITHOUT creating an
 * order. There is no server checkout-preview endpoint — the only pricing
 * call (`POST /merchants/{id}/checkout`) creates an order and a Stripe
 * client secret, which the assistant must never touch — so taxes and the
 * delivery fee cannot be known here: [taxesCents] / [deliveryFeeCents] are
 * null when unknown (delivery fee is exactly 0 for pickup), [promo] is
 * echoed but not valued, and [totalCents] is the sum of the known parts
 * (subtotal + tip + known delivery fee). The authoritative total is shown
 * in-app at `fooddelivery://checkout` ([checkoutDeepLink]), where the user
 * places and pays for the order.
 */
@Serializable
data class OrderSummaryData(
    val merchantId: Int = 0,
    val merchantName: String = "",
    val isPickup: Boolean = false,
    val addressId: String? = null,
    val items: List<CartItemData> = emptyList(),
    val subtotalCents: Int = 0,
    val deliveryFeeCents: Int? = null,
    val taxesCents: Int? = null,
    val tipCents: Int = 0,
    val promo: String? = null,
    val totalCents: Int = 0,
    val checkoutDeepLink: String = "fooddelivery://checkout",
    val error: String? = null,
)

/** Read the signed-in customer's past orders. No fields. */
@Serializable
data class GetOrders(
    val unused: Boolean = false,
)

/** One past order as the assistant sees it. */
@Serializable
data class OrderData(
    val id: Int = 0,
    val uuid: String? = null,
    val merchantId: Int = 0,
    val merchantName: String = "",
    val status: String = "",
    val totalCents: Int = 0,
    val itemCount: Int = 0,
    val createdAt: String? = null,
    val isDelivery: Boolean = false,
    val isDone: Boolean = false,
)
