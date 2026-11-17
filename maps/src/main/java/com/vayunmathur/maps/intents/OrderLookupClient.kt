package com.vayunmathur.maps.intents

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.vayunmathur.library.intents.fooddelivery.OrderLookupRequest
import com.vayunmathur.library.intents.fooddelivery.OrderLookupResult
import com.vayunmathur.maps.data.SpecificFeature

/** Package of the MA fooddelivery app. */
private const val FOODDELIVERY_PACKAGE = "com.vayunmathur.fooddelivery"
private const val ORDER_LOOKUP_CLASS = "com.vayunmathur.fooddelivery.intents.OrderLookupIntent"

/** Result of an orderable lookup: whether it's orderable and, if so, its deep link. */
data class OrderInfo(val orderable: Boolean, val deepLinkUri: String?)

/**
 * Reads fooddelivery's exported orderable-lookup intent
 * (`com.vayunmathur.fooddelivery.intents.OrderLookupIntent`) for a place at [name] / [lat] / [lng].
 *
 * Absence handling is total: if fooddelivery isn't installed the launch reports a missing package
 * ([MissingAppException]); if the signature permission isn't held it throws [SecurityException]; a
 * timeout or malformed reply throws too. In every one of those cases this returns null and the
 * caller simply shows no Order button — no crash.
 */
object OrderLookupClient {
    suspend fun lookup(
        context: Context,
        name: String,
        lat: Double,
        lng: Double,
        address: String? = null,
    ): OrderInfo? {
        if (name.isBlank()) return null
        return try {
            val result: OrderLookupResult = launchIntent(
                context,
                FOODDELIVERY_PACKAGE,
                ORDER_LOOKUP_CLASS,
                OrderLookupRequest(name, lat, lng, address?.ifBlank { null }),
            )
            if (!result.available) {
                OrderInfo(false, null)
            } else {
                OrderInfo(true, result.deepLink.ifBlank { null })
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }
}

/** Food categories worth checking for orderability. */
private val FOOD_KEYWORDS = listOf(
    "restaurant", "food", "dining", "pizza", "burger", "steak", "sushi",
    "cafe", "coffee", "bakery", "tea", "dessert",
)

private fun isFoodCategory(category: String?): Boolean {
    val c = category?.lowercase() ?: return false
    return FOOD_KEYWORDS.any { it in c }
}

/** Whether this selection is worth asking fooddelivery about at all. */
private fun isOrderableCandidate(feature: SpecificFeature.RoutableFeature, category: String?): Boolean =
    feature is SpecificFeature.Restaurant || isFoodCategory(category)

/**
 * For a selected restaurant/food place, resolve fooddelivery's order deep link
 * (or null if it isn't a food place, isn't orderable, or fooddelivery is absent).
 *
 * The lookup runs off the main thread; the state re-resolves whenever the selection or its
 * enrichment category changes. Non-food places short-circuit without ever launching the intent.
 */
@Composable
fun rememberOrderDeepLink(
    context: Context,
    feature: SpecificFeature.RoutableFeature,
    category: String?,
): State<String?> = produceState<String?>(null, feature.name, feature.position, category) {
    value = null
    if (!isOrderableCandidate(feature, category)) return@produceState
    val info = OrderLookupClient.lookup(
        context, feature.name, feature.position.latitude, feature.position.longitude,
    )
    value = info?.takeIf { it.orderable }?.deepLinkUri
}
