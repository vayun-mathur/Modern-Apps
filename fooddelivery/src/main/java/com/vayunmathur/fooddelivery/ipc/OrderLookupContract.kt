package com.vayunmathur.fooddelivery.ipc

/**
 * Wire contract for the `fooddelivery://restaurant/<id>` cross-app deep link that opens a specific
 * merchant's order page (e.g. from the maps place-sheet "Order" button, or returned by
 * `com.vayunmathur.fooddelivery.intents.OrderLookupIntent`).
 *
 * The "is this restaurant orderable?" lookup that used to live here as a signature-guarded
 * ContentProvider is now the `OrderLookupIntent`
 * [AssistantIntent][com.vayunmathur.library.util.AssistantIntent] — a request/response fits
 * time-varying catalog data better than a cursor. Only the deep link remains here; it is parsed on
 * the way in by [com.vayunmathur.fooddelivery.MainActivity].
 */
object OrderLookupContract {
    // ---- deep link (open a specific restaurant's order page) ----
    const val DEEP_LINK_SCHEME = "fooddelivery"
    const val DEEP_LINK_HOST = "restaurant"

    /** Build the deep link that opens merchant [id]'s order page. */
    fun deepLink(id: Int): String = "$DEEP_LINK_SCHEME://$DEEP_LINK_HOST/$id"

    // ---- deep link (open checkout for the current cart, assistant handoff) ----
    const val DEEP_LINK_CHECKOUT_HOST = "checkout"

    /** Build the deep link that opens checkout for the current cart. */
    fun checkoutDeepLink(): String = "$DEEP_LINK_SCHEME://$DEEP_LINK_CHECKOUT_HOST"
}
