package com.vayunmathur.uniflix.data

import android.content.Context
import android.net.Uri
import com.vayunmathur.library.image.fetchers.FetchResult
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HTTP(S) source for the shared `:library:image` pipeline, owned by this app.
 *
 * Lives here rather than in a library so no library-to-library edge (and no
 * leaked permission) is needed: uniflix declares INTERNET itself, depends on
 * both `:library:image` (the `Fetcher` SPI) and `:library:network`
 * (`NetworkClient` with the `STANDARD` bundle), and registers this on its
 * `ImageLoader` for catalog/stremio artwork. Returns null for non-HTTP data
 * so it composes with the pipeline's local fetchers (#884).
 */
object RemoteImageFetchers {
    suspend fun network(data: Any?, @Suppress("UNUSED_PARAMETER") context: Context): FetchResult? {
        val url: String = when (data) {
            is String -> data
            is Uri -> {
                val s = data.scheme
                if (s == "http" || s == "https") data.toString() else return null
            }
            else -> return null
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return withContext(Dispatchers.IO) {
            var resp = runCatching { NetworkClient.execute(url) }
            // TMDB artwork is read from its static R2 copy first; one that does not
            // exist yet (or an unreachable static host) falls back to the image proxy.
            val proxy = TmdbImageUrl.proxyFor(url)
            if (proxy != null && resp.getOrNull()?.isSuccess != true) {
                resp = runCatching { NetworkClient.execute(proxy) }
            }
            val ok = resp.getOrThrow()
            if (!ok.isSuccess) {
                throw java.io.IOException("HTTP ${ok.status} for ${proxy ?: url}")
            }
            FetchResult.Bytes(ok.bytes)
        }
    }
}
