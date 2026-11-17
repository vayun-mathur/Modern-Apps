package com.vayunmathur.openassistant.network

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.Urls
import com.vayunmathur.openassistant.platform.A2uiTemplates
import com.vayunmathur.openassistant.platform.SearchResultItem
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * Wire contract for GET https://api.maproj.org/api/search
 * (copied from web/data/WebSearch.kt shapes; openassistant must not depend on the web app).
 */
@Serializable
data class WebSearchResponse(
    @SerialName("type") val kind: String = "text",
    val query: String = "",
    val text: List<WebSearchTextItem>? = null,
    val images: List<WebSearchImageItem>? = null,
    val news: List<WebSearchNewsItem>? = null,
    val error: String? = null,
    @SerialName("fallback_url") val fallbackUrl: String? = null,
)

@Serializable
data class WebSearchTextItem(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
)

@Serializable
data class WebSearchImageItem(
    val title: String = "",
    @SerialName("image_url") val imageUrl: String = "",
    val thumbnail: String = "",
    @SerialName("source_url") val sourceUrl: String = "",
)

@Serializable
data class WebSearchNewsItem(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
    val date: Long = 0L,
    val source: String = "",
)

/** Wire contract for GET https://api.maproj.org/assistant/v1/read. */
@Serializable
data class WebReadResponse(
    val title: String = "",
    val url: String = "",
    val text: String = "",
    val truncated: Boolean = false,
    @SerialName("next_offset") val nextOffset: Int? = null,
)

/** One stored search hit (host-side only; index is 1-based for the model). */
data class WebHit(
    val index: Int,
    val title: String,
    val url: String,
    val snippet: String,
    val domain: String,
)

/**
 * Web search + page reading (Phase W). All READ tools.
 *
 * Transport seam for tests: [transport] fetches a URL and returns the body text.
 * Production defaults to a [NetworkClient] GET (FIRST_PARTY, inited in MainActivity).
 * Never throws to the model: failures come back as clean "Error: ..." strings.
 */
class AssistantWebTools(
    private val transport: suspend (url: String) -> String = { url -> defaultTransport(url) },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private var lastHits: List<WebHit> = emptyList()
    private var lastAtMillis: Long = 0L
    private var lastQuery: String = ""

    /** A2UI searchResults template stashed by the last successful search, if any. */
    var pendingTemplate: String? = null
        private set

    fun takeTemplate(): String? {
        val t = pendingTemplate
        pendingTemplate = null
        return t
    }

    /** Snapshot for tests / host code. */
    fun lastResults(): List<WebHit> = lastHits

    suspend fun webSearch(query: String, count: Int = 5, kind: String = "text"): String {
        val clamped = count.coerceIn(1, 10)
        val type = if (kind.equals("news", ignoreCase = true)) "news" else "text"
        val url = Urls.appendQuery(
            SEARCH_URL,
            mapOf(
                "q" to query,
                "type" to type,
                "safe_search" to "moderate",
                "page" to "1",
            ),
        )
        val body = try {
            transport(url)
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            return "Error: search unavailable: ${expected.message ?: "network error"}"
        }
        val response = try {
            json.decodeFromString<WebSearchResponse>(body)
        } catch (expected: Exception) {
            return "Error: search unavailable: could not parse search response"
        }
        if (!response.error.isNullOrBlank()) {
            val fb = response.fallbackUrl?.takeIf { it.isNotBlank() } ?: FALLBACK_URL
            return "Error: search unavailable: ${response.error} (fallback: $fb)"
        }
        val raw: List<Pair<String, Pair<String, String>>> = when (type) {
            "news" -> response.news.orEmpty().map { it.title to (it.url to it.snippet) }
            else -> response.text.orEmpty().map { it.title to (it.url to it.snippet) }
        }
        val hits = raw.take(clamped).mapIndexed { i, (title, urlSnippet) ->
            val (hitUrl, snippet) = urlSnippet
            WebHit(i + 1, title, hitUrl, snippet, domainOf(hitUrl))
        }
        lastHits = hits
        lastAtMillis = nowMillis()
        lastQuery = query
        mirrorShared(hits, lastAtMillis, query)
        pendingTemplate = A2uiTemplates.searchResults(
            query,
            hits.map { SearchResultItem(it.index, it.title, it.snippet, it.domain, it.url) },
        )
        if (hits.isEmpty()) return "No results for \"$query\""
        return formatSearchResults(hits)
    }

    suspend fun webRead(url: String, offset: Int = 0): String {
        if (url.isBlank()) return "Error: could not read page: empty url"
        val full = Urls.appendQuery(
            READ_URL,
            buildMap {
                put("url", url)
                if (offset > 0) put("offset", offset.toString())
            },
        )
        val body = try {
            transport(full)
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            return "Error: could not read page: ${expected.message ?: "network error"}"
        }
        val parsed = try {
            json.decodeFromString<WebReadResponse>(body)
        } catch (expected: Exception) {
            return "Error: could not read page: could not parse reader response"
        }
        return formatReadResult(
            parsed.title,
            parsed.url.ifBlank { url },
            parsed.text,
            parsed.truncated,
            parsed.nextOffset,
        )
    }

    suspend fun webReadResult(index: Int, offset: Int = 0): String {
        val hits = resolveHits()
        if (hits == null) {
            return "Error: no recent search results - run web_search first"
        }
        val hit = hits.firstOrNull { it.index == index }
            ?: return "Error: no result at index $index in the last search"
        val body = webRead(hit.url, offset)
        // webRead already formats title/url; keep its clean error strings untouched.
        return body
    }

    /** Formatted extraction for the ViewModel host-intercept (same caps as [webReadResult]). */
    suspend fun webReadResultText(index: Int): String = webReadResult(index)

    private fun resolveHits(): List<WebHit>? {
        val now = nowMillis()
        if (lastHits.isNotEmpty() && now - lastAtMillis <= EXPIRY_MILLIS) return lastHits
        val shared = sharedHits
        if (shared.isNotEmpty() && now - sharedAtMillis <= EXPIRY_MILLIS) {
            lastHits = shared
            lastAtMillis = sharedAtMillis
            lastQuery = sharedQuery
            return shared
        }
        return null
    }

    companion object {
        const val SEARCH_URL = "https://api.maproj.org/api/search"
        const val READ_URL = "https://api.maproj.org/assistant/v1/read"
        const val FALLBACK_URL = "https://html.duckduckgo.com/html/"
        const val EXPIRY_MILLIS: Long = 30 * 60_000L
        const val MAX_SEARCH_CHARS = 3000
        const val MAX_READ_CHARS = 6000

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        // Process-wide mirror so a fresh AssistantWebTools in the ViewModel
        // (host web.read intercept) can resolve indices from the search the
        // InferenceService-side instance stored. One conversation at a time
        // (MamlChatEngine mutex) makes a single shared slot safe for v1.
        @Volatile private var sharedHits: List<WebHit> = emptyList()
        @Volatile private var sharedAtMillis: Long = 0L
        @Volatile private var sharedQuery: String = ""

        private fun mirrorShared(hits: List<WebHit>, at: Long, query: String) {
            sharedHits = hits
            sharedAtMillis = at
            sharedQuery = query
        }

        /** Test hook: clears the process-wide mirror. */
        fun clearSharedForTest() {
            sharedHits = emptyList()
            sharedAtMillis = 0L
            sharedQuery = ""
        }

        suspend fun defaultTransport(url: String): String {
            val response = NetworkClient.performRequest(url)
            if (!response.isSuccess) {
                throw java.io.IOException("HTTP ${response.status}")
            }
            return response.body
        }

        fun domainOf(url: String): String =
            runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrNull().orEmpty()

        /** Numbered, host-capped rendering of [hits] for the model. */
        fun formatSearchResults(hits: List<WebHit>): String {
            val out = StringBuilder()
            for (hit in hits) {
                val line = "[${hit.index}] ${hit.title} — ${hit.domain}\n${hit.url}\n${hit.snippet}\n"
                if (out.length + line.length > MAX_SEARCH_CHARS) break
                out.append(line)
            }
            return out.toString().trimEnd()
        }

        /** Host-capped page rendering; appends a truncation note when the server says so. */
        fun formatReadResult(title: String, url: String, text: String, truncated: Boolean, nextOffset: Int?): String {
            val capped = text.take(MAX_READ_CHARS)
            val header = listOf(title.takeIf { it.isNotBlank() }, url).mapNotNull { it }.joinToString("\n")
            val body = if (header.isBlank()) capped else "$header\n\n$capped"
            val note = when {
                truncated && nextOffset != null -> "\n\n[truncated - continue with offset $nextOffset]"
                truncated || text.length > MAX_READ_CHARS -> "\n\n[truncated]"
                else -> ""
            }
            return body + note
        }
    }
}
