package com.vayunmathur.web.platform

import android.net.Uri
import androidx.core.net.toUri

/**
 * Native self-hosted search tab URLs.
 *
 * A `search://results?q=...` pseudo-URL in a tab means "render [SearchResultsPage]
 * natively" rather than loading engine HTML in a WebView. Living in `platform/`
 * (not `ui/`) so the tab model, omnibox chrome, and tab switcher can all share
 * them without `platform` depending on `ui`.
 */

/** Builds the pseudo-URL stored in a tab for a native self-hosted search. */
fun nativeSearchUrl(query: String): String =
    "search://results?q=" + Uri.encode(query)

/** Whether [url] is a native self-hosted search page rather than a WebView page. */
fun isNativeSearchUrl(url: String): Boolean = url.startsWith("search://")

/** Extracts the query from a [nativeSearchUrl], or "" if it is not one. */
fun nativeSearchQuery(url: String): String {
    if (!isNativeSearchUrl(url)) return ""
    return runCatching { url.toUri().getQueryParameter("q").orEmpty() }.getOrDefault("")
}

/**
 * What the omnibox pill and tab tiles show for [url]: the query for a native
 * search tab, "" for blank tabs, the raw URL otherwise.
 */
fun displayTextForUrl(url: String): String {
    if (url.isBlank() || url == "about:blank") return ""
    return nativeSearchQuery(url).ifBlank { url }
}
