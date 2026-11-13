package com.vayunmathur.uniflix.data

import com.vayunmathur.library.network.StaticMirror

/**
 * TMDB artwork — no direct image.tmdb.org. Sizes: w185/w342 cards, w500 search, original hero.
 *
 * URLs point at the server's static copy on R2 (images are content-addressed, so a copy
 * never goes stale); [RemoteImageFetchers] falls back to the self-hosted image proxy
 * ([proxyFor]) when the copy does not exist yet, and the proxy then creates it.
 */
object TmdbImageUrl {
    private const val BASE = "${StaticMirror.BASE}/tmdb/v1/image/"
    private const val PROXY_BASE = "https://api.maproj.org/api/tmdb/image/"

    fun poster(path: String?, size: String = "w342"): String? =
        path?.let { "$BASE$size$it" }

    fun backdrop(path: String?): String? =
        path?.let { "${BASE}original$it" }

    fun still(path: String?, size: String = "w185"): String? =
        path?.let { "$BASE$size$it" }

    /** The image proxy URL for a static artwork [url], or null for any other URL. */
    fun proxyFor(url: String): String? =
        url.takeIf { it.startsWith(BASE) }?.let { PROXY_BASE + it.removePrefix(BASE) }
}
