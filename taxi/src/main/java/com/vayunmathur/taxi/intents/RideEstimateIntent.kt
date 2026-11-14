package com.vayunmathur.taxi.intents

import com.vayunmathur.library.intents.taxi.RideEstimateRequest
import com.vayunmathur.library.intents.taxi.RideEstimateResult
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.taxi.data.LatLng
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.provider.QuoteRepository
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

/**
 * Exported, signature-guarded estimate lookup that answers "can I get a ride for this
 * origin→destination, and roughly what fare/ETA?" for a co-signed app (the maps route sheet).
 *
 * Replaces the old `RideEstimateProvider` cursor: a live quote is time-varying data, so a
 * request/response [AssistantIntent] fits it better than a ContentProvider. It reuses the app's own
 * quote pipeline ([QuoteRepository.quotes]) and returns the cheapest live quote as
 * `available=true` with its formatted fare + pickup ETA, or `available=false` (empty fare, eta -1)
 * when there is no fare / the caller isn't signed in / anything fails. Wrapping keeps a network or
 * parse failure from crossing the binder — the caller then simply shows the launch-only option.
 *
 * Guarded by `com.vayunmathur.taxi.permissions.ACCESS_RIDES` in the manifest.
 */
@OptIn(InternalSerializationApi::class)
class RideEstimateIntent : AssistantIntent<RideEstimateRequest, RideEstimateResult>(
    serializer<RideEstimateRequest>(),
    serializer<RideEstimateResult>(),
) {
    override suspend fun performCalculation(input: RideEstimateRequest): RideEstimateResult {
        val quote = try {
            bestQuote(input)
        } catch (_: Exception) {
            null
        }
        return if (quote != null) {
            RideEstimateResult(true, formatFare(quote), quote.pickupEtaMinutes ?: -1)
        } else {
            RideEstimateResult(false, "", -1)
        }
    }

    private suspend fun bestQuote(input: RideEstimateRequest): RideQuote? {
        // This activity can run in a cold taxi process (launched straight into the caller's task),
        // so the network stack may not be warmed up yet — MainActivity does the same on its path.
        try {
            NetworkClient.init(applicationContext, TrustBundle.STANDARD)
        } catch (_: Exception) {
            // Best-effort; a lookup that then fails just returns an unavailable result.
        }
        val pickup = Place(input.pickupLabel.orEmpty(), null, LatLng(input.pickupLat, input.pickupLng))
        val dropoff = Place(input.destLabel.orEmpty(), null, LatLng(input.destLat, input.destLng))
        // Bound the network quote so a slow/hung upstream can't hold the call open indefinitely; a
        // timeout degrades to an unavailable result like any other failure.
        val results = withTimeoutOrNull(ESTIMATE_TIMEOUT_MS) {
            QuoteRepository.quotes(applicationContext, pickup, dropoff)
        } ?: return null
        return results.values
            .filterIsInstance<QuoteResult.Success>()
            .flatMap { it.quotes }
            .minByOrNull { it.fareLowMinor }
    }

    private fun formatFare(quote: RideQuote): String {
        fun money(minor: Long) = "$%.2f".format(minor / CENTS_PER_DOLLAR)
        return if (quote.fareLowMinor != quote.fareHighMinor) {
            "${money(quote.fareLowMinor)} – ${money(quote.fareHighMinor)}"
        } else {
            money(quote.fareLowMinor)
        }
    }

    /** Shared constants and helpers for RideEstimateIntent. */
    companion object {
        /** Upper bound on the upstream quote so the call can't hang. */
        private const val ESTIMATE_TIMEOUT_MS = 5_000L
        private const val CENTS_PER_DOLLAR = 100.0
    }
}
