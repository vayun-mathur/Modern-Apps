package com.vayunmathur.web.data

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.Urls

/**
 * Client for the self-hosted search API
 * (GET https://api.maproj.org/api/search, see
 * `location_share_server/src/handlers/search.rs`).
 *
 * Goes through [NetworkClient] like all app networking — never raw
 * OkHttp/`HttpURLConnection`. `web/` inits `TrustBundle.SYSTEM`, which
 * covers our first-party host too.
 */
object WebSearchRepository {

    const val BASE_URL = "https://api.maproj.org/api/search"

    /**
     * Test seam: when set, [search] delegates here instead of the network.
     * Production never sets it; unit tests use it to drive the search pages
     * deterministically (no live api.maproj.org in the test environment).
     */
    internal var searchOverride: (suspend (query: String, kind: WebSearchKind, safeSearch: Boolean, page: Int) -> WebSearchResponse)? = null

    /**
     * Runs one search. `safeSearch=true` (parental supervision switch) maps to
     * the server's strict mode; otherwise moderate. Throws IOException on
     * transport failure — callers show the error state with a fallback button.
     * A degraded server reply (upstream DDG blocked) still parses: [WebSearchResponse.error]
     * is set and the result lists are empty.
     */
    suspend fun search(
        query: String,
        kind: WebSearchKind = WebSearchKind.TEXT,
        safeSearch: Boolean = false,
        page: Int = 1,
    ): WebSearchResponse {
        searchOverride?.let { return it(query, kind, safeSearch, page) }
        val url = Urls.appendQuery(
            BASE_URL,
            mapOf(
                "q" to query,
                "type" to kind.param,
                "safe_search" to if (safeSearch) "on" else "moderate",
                "page" to page.coerceAtLeast(1).toString(),
            ),
        )
        return NetworkClient.getJson(url)
    }
}
