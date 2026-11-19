package com.vayunmathur.library.image.fetchers

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HTTP(S) source for the shared image pipeline, shared by every app that
 * loads remote artwork.
 *
 * This used to be copied per app as a `RemoteImageFetchers` object (eight
 * near-identical copies, ~40 lines each). The one part that genuinely
 * differs per app is the transport, so it is injected: the app hands in
 * its `NetworkClient` bytes call (it already depends on
 * `:library:network` and declares INTERNET itself), and everything else —
 * which data counts as remote, the IO hop, the fallback retry — lives
 * here. This module stays permission-free: no network code is linked
 * into offline consumers of the pipeline (#884).
 *
 * Returns null for data that is not an http(s) URL so it composes with
 * the pipeline's local fetchers. A failed fetch throws; pass
 * [fallbackUrl] for sources with a mirror/proxy form of the same URL
 * (uniflix's TMDB proxy) and the fallback is tried once before the
 * failure propagates.
 */
object HttpImageFetcher {

    suspend fun fetch(
        data: Any?,
        transport: suspend (String) -> ByteArray,
        fallbackUrl: (String) -> String? = { null },
    ): FetchResult? {
        val url: String = when (data) {
            is String -> data
            is Uri -> {
                val scheme = data.scheme
                if (isHttpScheme(scheme)) data.toString() else return null
            }
            else -> return null
        }
        if (!isHttpUrl(url)) return null
        return withContext(Dispatchers.IO) {
            val bytes = try {
                transport(url)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                val fallback = fallbackUrl(url) ?: throw failure
                transport(fallback)
            }
            FetchResult.Bytes(bytes)
        }
    }

    private fun isHttpScheme(scheme: String?): Boolean =
        scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
}
