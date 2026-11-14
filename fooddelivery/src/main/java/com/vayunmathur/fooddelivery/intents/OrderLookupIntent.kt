package com.vayunmathur.fooddelivery.intents

import com.vayunmathur.fooddelivery.api.BitesMerchants
import com.vayunmathur.fooddelivery.data.Merchant
import com.vayunmathur.fooddelivery.ipc.OrderLookupContract
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.intents.fooddelivery.OrderLookupRequest
import com.vayunmathur.library.intents.fooddelivery.OrderLookupResult
import com.vayunmathur.library.util.AssistantIntent
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Exported, signature-guarded lookup that answers "can I order from this restaurant?" for a
 * co-signed app (the maps place sheet).
 *
 * Replaces the old `OrderLookupProvider` cursor: a live catalog match is time-varying data, so a
 * request/response [AssistantIntent] fits it better than a ContentProvider. It fetches the merchant
 * catalog near the queried point ([BitesMerchants.getMerchants]) and does a tolerant name-normalise +
 * proximity match, returning the nearest match within [MATCH_RADIUS_METERS] as `available=true`
 * with its id and `fooddelivery://restaurant/<id>` deep link, or `available=false` otherwise.
 * A network/parse failure degrades to a not-orderable result rather than crossing the binder.
 *
 * Guarded by `com.vayunmathur.fooddelivery.permissions.ACCESS_ORDERS` in the manifest.
 */
@OptIn(InternalSerializationApi::class)
class OrderLookupIntent : AssistantIntent<OrderLookupRequest, OrderLookupResult>(
    serializer<OrderLookupRequest>(),
    serializer<OrderLookupResult>(),
) {
    override suspend fun performCalculation(input: OrderLookupRequest): OrderLookupResult {
        val match = try {
            findMatch(input)
        } catch (_: Exception) {
            null
        }
        return if (match != null) {
            OrderLookupResult(true, match.id, OrderLookupContract.deepLink(match.id))
        } else {
            OrderLookupResult(false, 0, "")
        }
    }

    private suspend fun findMatch(input: OrderLookupRequest): Merchant? {
        val target = normalize(input.name.trim())
        if (target.isEmpty()) return null

        // This can run in a cold fooddelivery process; the trust bundle + saved auth token are
        // warmed up off the main thread by AppInit (idempotent), which we await before the fetch.
        AppInit.start(applicationContext)
        AppInit.awaitReady()
        val merchants = BitesMerchants.getMerchants(input.lat, input.lng)
        if (merchants.isEmpty()) return null

        // Keep only merchants that are plausibly the same place: close enough AND whose normalised
        // name matches (equal / prefix / containment). Pick the nearest such candidate.
        return merchants
            .mapNotNull { m ->
                val d = haversineMeters(input.lat, input.lng, m.latitude, m.longitude)
                if (d <= MATCH_RADIUS_METERS && nameMatches(target, normalize(m.name))) m to d else null
            }
            .minByOrNull { it.second }
            ?.first
    }

    private fun nameMatches(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    /** Lowercase and strip everything but a–z/0–9 so "Joe's Café" ≈ "joes cafe". */
    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

        /** Matching radius used to identify the merchant at a queried location. */
    companion object {
        /** How close a merchant must be to the queried point to be the same place. */
        private const val MATCH_RADIUS_METERS = 800.0
    }
}
