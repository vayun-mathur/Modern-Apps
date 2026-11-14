package com.vayunmathur.taxi.provider

import android.content.Context
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.Provider
import com.vayunmathur.taxi.data.QuoteResult
import com.vayunmathur.taxi.network.lyft.LyftProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Singleton holding quote repository state and operations. */
object QuoteRepository {

    /**
     * Quotes every enabled provider concurrently. One provider failing must not hide another's
     * prices, so every provider always yields a result rather than throwing. Uber is hidden
     * from the UI, so only Lyft is quoted; its module code stays compiled but unreferenced.
     */
    suspend fun quotes(
        context: Context,
        pickup: Place,
        dropoff: Place,
    ): Map<Provider, QuoteResult> = coroutineScope {
        val providers = listOf(
            LyftProvider(context.applicationContext),
        )
        providers
            .map { provider ->
                provider.provider to async {
                    runCatching { provider.quotes(pickup, dropoff) }
                        .getOrElse { QuoteResult.Failed(it.message ?: "Request failed") }
                }
            }
            .associate { (key, deferred) -> key to deferred.await() }
    }

    /**
     * Re-quotes to refresh cost tokens that are about to expire, reusing the previous offers
     * session so the server returns the same products with fresh tokens. Falls back to a full
     * quote if the update path returns nothing. Lyft only, mirroring [quotes].
     */
    suspend fun refresh(
        context: Context,
        pickup: Place,
        dropoff: Place,
        previous: Map<Provider, QuoteResult>,
    ): Map<Provider, QuoteResult> {
        val lyft = LyftProvider(context.applicationContext)
        val prev = previous[Provider.LYFT] as? QuoteResult.Success
        val updated = runCatching {
            lyft.updateQuotes(pickup, dropoff, prev?.offersResponseId, prev?.purchaseSessionId)
        }.getOrElse { QuoteResult.Failed(it.message ?: "Request failed") }
        val result = if (updated is QuoteResult.Success) {
            updated
        } else {
            runCatching { lyft.quotes(pickup, dropoff) }.getOrElse { updated }
        }
        return mapOf(Provider.LYFT to result)
    }
}
