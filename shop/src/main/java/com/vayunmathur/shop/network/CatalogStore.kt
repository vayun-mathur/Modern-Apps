package com.vayunmathur.shop.network

import android.content.Context
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.shop.data.ShopCatalog
import com.vayunmathur.shop.data.ShopCatalogFile
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Downloads and caches the R2 offline catalog
 * (`static.maproj.org/shop/v1/catalog.json`). The file lives in
 * app-private storage; a refresh replaces it only when the server's
 * `generated_at` is newer, so offline browsing survives with no
 * network and costs one conditional download per app run at most.
 * Checkout never reads this: it stays on [ShopBackend]'s live calls.
 */
object CatalogStore {
    const val CATALOG_URL = "https://static.maproj.org/shop/v1/catalog.json"
    private const val FILE_NAME = "shop-catalog.json"
    private val json = Json { ignoreUnknownKeys = true }

    /** Injectable for tests; the production fetcher uses the shared stack. */
    internal var fetch: suspend (String) -> String? = { url ->
        runCatching {
            withTimeout(30_000) {
                NetworkClient.execute(
                    url, "GET",
                    headers = mapOf("Accept" to "application/json"),
                    followRedirects = true,
                    connectTimeoutMs = 10_000,
                    readTimeoutMs = 20_000,
                )
            }.takeIf { it.status in 200..299 }?.text
        }.getOrNull()
    }

    fun parse(body: String): ShopCatalogFile? =
        runCatching { json.decodeFromString<ShopCatalogFile>(body) }
            .getOrNull()
            ?.takeIf(ShopCatalog::isUsable)

    fun cached(context: Context): ShopCatalogFile? =
        runCatching { parse(File(context.filesDir, FILE_NAME).readText()) }.getOrNull()

    /** Newer-catalog-wins merge decision, pure for testing. */
    fun shouldReplace(current: ShopCatalogFile?, incoming: ShopCatalogFile): Boolean =
        current == null ||
            ShopCatalog.generatedAtMs(incoming) > ShopCatalog.generatedAtMs(current)

    suspend fun refresh(context: Context): ShopCatalogFile? {
        val current = cached(context)
        val body = fetch(CATALOG_URL) ?: return current
        val incoming = parse(body) ?: return current
        if (!shouldReplace(current, incoming)) return current
        runCatching { File(context.filesDir, FILE_NAME).writeText(body) }
        return incoming
    }
}
