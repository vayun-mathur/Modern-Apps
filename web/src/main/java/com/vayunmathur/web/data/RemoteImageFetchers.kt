package com.vayunmathur.web.data

import android.content.Context
import android.net.Uri
import com.vayunmathur.library.image.fetchers.FetchResult
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.web.platform.BrowserUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HTTP(S) source for the shared `:library:image` pipeline, owned by this app.
 *
 * Lives here rather than in a library so no library-to-library edge (and no
 * leaked permission) is needed: the browser declares INTERNET itself, depends
 * on both `:library:image` (the `Fetcher` SPI) and `:library:network`
 * (`NetworkClient` with the `SYSTEM` bundle for user-entered hosts), and
 * registers this on its `ImageLoader` for search-result thumbnails and PWA
 * icon fetches. Returns null for non-HTTP data so it composes with the
 * pipeline's local fetchers (#884).
 */
object RemoteImageFetchers {
    suspend fun network(data: Any?, @Suppress("UNUSED_PARAMETER") context: Context): FetchResult? {
        val url: String = when (data) {
            is String -> data
            is Uri -> {
                val s = data.scheme
                if (BrowserUtils.isWebScheme(s)) data.toString() else return null
            }
            else -> return null
        }
        if (!BrowserUtils.isWebUrl(url)) return null
        return withContext(Dispatchers.IO) {
            val resp = NetworkClient.execute(url)
            if (!resp.isSuccess) {
                throw java.io.IOException("HTTP ${resp.status} for $url")
            }
            FetchResult.Bytes(resp.bytes)
        }
    }
}
