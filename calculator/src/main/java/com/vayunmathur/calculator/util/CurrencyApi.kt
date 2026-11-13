package com.vayunmathur.calculator.util

import com.vayunmathur.library.network.StaticMirror
import kotlinx.serialization.Serializable

/**
 * Live currency exchange rates from the self-hosted proxy on `api.maproj.org`, which
 * fetches and caches them from a free upstream; read from the server's static copy on
 * R2 first (same JSON, refreshed hourly), with the endpoint as the fallback. Rates are "units of the currency per 1
 * [base]" (base is USD, so USD == 1.0), which is exactly the `factorToBase` inverse the
 * converter needs — see [UnitRegistry.currencyCategory].
 */
object CurrencyApi {

    private const val URL = "https://api.maproj.org/api/currency/rates"

    /** The server's hourly copy of the same JSON on R2 (read first). */
    private const val STATIC_URL = "${StaticMirror.BASE}/rates/v1/currency.json"

    /** Older static copies mean the server stopped refreshing them: ask it directly. */
    private const val STATIC_MAX_AGE_MS = 2L * 24 * 60 * 60 * 1000

    suspend fun rates(): CurrencyRatesDto = StaticMirror.getJson(STATIC_URL, URL, STATIC_MAX_AGE_MS)
}

@Serializable
data class CurrencyRatesDto(
    val base: String = "USD",
    val updated: Long = 0L,
    val rates: Map<String, Double> = emptyMap(),
)
