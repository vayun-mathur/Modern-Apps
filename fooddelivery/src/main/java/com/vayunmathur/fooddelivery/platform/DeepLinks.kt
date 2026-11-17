package com.vayunmathur.fooddelivery.platform

import android.content.Intent
import com.vayunmathur.fooddelivery.Route
import com.vayunmathur.fooddelivery.intents.OrderLookupContract
import com.vayunmathur.fooddelivery.notifications.OrderLiveUpdate

internal fun Intent.trackOrderIdOrNull(): Int? =
    getIntExtra(OrderLiveUpdate.EXTRA_TRACK_ORDER_ID, -1).takeIf { it > 0 }

// fooddelivery://restaurant/<id> — the cross-app "open this restaurant's order
// page" deep link (see OrderLookupContract). Returns null for anything else.
internal fun Intent.restaurantIdOrNull(): Int? {
    val uri = data ?: return null
    if (uri.scheme != OrderLookupContract.DEEP_LINK_SCHEME) return null
    if (uri.host != OrderLookupContract.DEEP_LINK_HOST) return null
    return uri.lastPathSegment?.toIntOrNull()?.takeIf { it > 0 }
}

// fooddelivery://checkout — opens Route.Checkout for the current cart (the
// assistant's order handoff; payment still happens in-app via Stripe).
internal fun Intent.isCheckoutDeepLink(): Boolean {
    val uri = data ?: return false
    return uri.scheme == OrderLookupContract.DEEP_LINK_SCHEME &&
        uri.host == OrderLookupContract.DEEP_LINK_CHECKOUT_HOST
}

private const val EMAIL_VERIFICATION = "email-verification"

// Both segments end up in an API path, so only plain id characters are let through.
private val LINK_SEGMENT = Regex("[A-Za-z0-9_-]{1,256}")

/**
 * com.deliverycollective.storefront://email-verification/<customerId>/<token> or
 * https://withbites.com/email-verification/<customerId>/<token> — the same two forms the
 * official Bites app handles. Returns null for anything else.
 */
internal fun Intent.emailVerificationOrNull(): Route.EmailVerification? {
    val uri = data ?: return null
    val segments = when {
        uri.scheme == "com.deliverycollective.storefront" && uri.host == EMAIL_VERIFICATION ->
            uri.pathSegments
        uri.scheme == "https" && uri.pathSegments.firstOrNull() == EMAIL_VERIFICATION ->
            uri.pathSegments.drop(1)
        else -> return null
    }
    if (segments.size != 2 || !segments.all { LINK_SEGMENT.matches(it) }) return null
    return Route.EmailVerification(customerId = segments[0], token = segments[1])
}
