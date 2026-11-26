package com.vayunmathur.openassistant.network

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.Urls
import com.vayunmathur.openassistant.domain.UrlCheck
import com.vayunmathur.openassistant.domain.WebPageExtractor
import com.vayunmathur.openassistant.domain.WebReadUrlPolicy
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
 * Search stays server-backed via /api/search (also used by the Web app);
 * results are reshaped on device into the {title,url,snippet} hits the
 * tools consume. Page reading is fully on-device (see [webRead]).
 */
@Serializable
data class WebSearchResponse(
    @SerialName("type") val kind: String = "text",
    val query: String = "",
    val text: List<WebSearchTextItem>? = null,
    val images: List<WebSearchImageItem>? = null,
    val news: List<WebSearchNewsItem>? = null,
    val error: String? = null,
)

/** One text result in a web-search response. */
@Serializable
data class WebSearchTextItem(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
)

/** One image result in a web-search response. */
@Serializable
data class WebSearchImageItem(
    val title: String = "",
    @SerialName("image_url") val imageUrl: String = "",
    val thumbnail: String = "",
    @SerialName("source_url") val sourceUrl: String = "",
)

/** One news result in a web-search response. */
@Serializable
data class WebSearchNewsItem(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
    val date: Long = 0L,
    val source: String = "",
)

/** A page fetched on device for [AssistantWebTools.webRead]. */
data class FetchedPage(
    val body: String,
    val contentType: String? = null,
    val finalUrl: String = "",
    val status: Int = 200,
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
 * Web search + page reading. All READ tools.
 *
 * Search calls the server's GET /api/search directly (type=text, or
 * type=news for the news kind) through [NetworkClient] (FIRST_PARTY, inited
 * in MainActivity) and reshapes the text results on device. Read fetches
 * the page from the device and extracts readable text locally with
 * [WebPageExtractor]; arbitrary web hosts need system trust, so the page
 * fetch passes useSystemTrust per request while search keeps FIRST_PARTY.
 *
 * Transport seams for tests: [transport] fetches the search URL and returns
 * the body text; [pageTransport] fetches a page URL. Neither hits the live
 * network in tests. Never throws to the model: failures come back as clean
 * typed "Error: ..." strings (no silent empty results).
 */
class AssistantWebTools(
    private val transport: suspend (url: String) -> String = { url -> defaultTransport(url) },
    private val pageTransport: suspend (url: String) -> FetchedPage = { url -> defaultPageTransport(url) },
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
            return "Error: search unavailable: ${response.error}"
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

    /**
     * Reads [url] on device: validates it ([WebReadUrlPolicy]), fetches it
     * with size/timeout caps, then extracts and pages the readable text
     * locally. [offset] is a character offset into the extracted text.
     */
    suspend fun webRead(url: String, offset: Int = 0): String {
        val checked = when (val c = WebReadUrlPolicy.validate(url)) {
            is UrlCheck.Ok -> c.url
            is UrlCheck.Refused -> return "Error: could not read page: ${c.refusal.code}: ${c.refusal.message}"
        }
        val fetched = try {
            pageTransport(checked)
        } catch (e: CancellationException) {
            throw e
        } catch (expected: Exception) {
            return "Error: could not read page: fetch_failed: ${expected.message ?: "network error"}"
        }
        if (fetched.status !in 200..299) {
            return "Error: could not read page: fetch_failed: HTTP ${fetched.status}"
        }
        if (fetched.body.length > MAX_BODY_CHARS) {
            return "Error: could not read page: too_large: page is larger than the read limit"
        }
        val finalUrl = fetched.finalUrl.ifBlank { checked }
        // A redirect may have landed somewhere the policy refuses; recheck.
        WebReadUrlPolicy.refusalFor(finalUrl)?.let {
            return "Error: could not read page: ${it.code}: ${it.message}"
        }
        val kind = contentKind(fetched.contentType, fetched.body)
        val page = when (kind) {
            ContentKind.Unsupported ->
                return "Error: could not read page: unsupported_content_type: only HTML and plain-text pages can be read"
            ContentKind.PlainText -> WebPageExtractor.extractPlainText(fetched.body)
            ContentKind.Html -> WebPageExtractor.extract(fetched.body)
        }
        val (slice, truncated, nextOffset) = paginate(page.text, offset.coerceAtLeast(0))
        return formatReadResult(page.title, finalUrl, slice, truncated, nextOffset)
    }

    suspend fun webReadResult(index: Int, offset: Int = 0): String {
        val hits = resolveHits()
        if (hits == null) {
            return "Error: no recent search results - run web_search first"
        }
        val hit = hits.firstOrNull { it.index == index }
            ?: return "Error: no result at index $index in the last search"
        return webRead(hit.url, offset)
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

    /** Endpoints, limits, and shared search state for [AssistantWebTools]. */
    companion object {
        const val SEARCH_URL = "https://api.maproj.org/api/search"
        const val EXPIRY_MILLIS: Long = 30 * 60_000L
        const val MAX_SEARCH_CHARS = 3000
        const val MAX_READ_CHARS = 6000
        /** Hard cap on the fetched page body we will extract from (chars). */
        const val MAX_BODY_CHARS = 2 * 1024 * 1024
        const val FETCH_TIMEOUT_MS = 10_000L

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

        /**
         * Fetches an arbitrary web page. useSystemTrust is required: the
         * page host is not a first-party host, so the narrow FIRST_PARTY
         * bundle (set in MainActivity for /api/search) cannot validate it.
         */
        suspend fun defaultPageTransport(url: String): FetchedPage {
            val response = NetworkClient.execute(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0",
                    "Accept" to "text/html,application/xhtml+xml,text/plain",
                    "Accept-Language" to "en-US,en;q=0.9",
                ),
                followRedirects = true,
                connectTimeoutMs = FETCH_TIMEOUT_MS,
                readTimeoutMs = FETCH_TIMEOUT_MS,
                useSystemTrust = true,
            )
            return FetchedPage(
                body = response.text,
                contentType = response.header("Content-Type"),
                finalUrl = response.url,
                status = response.status,
            )
        }

        fun domainOf(url: String): String =
            runCatching { URI(url).host.orEmpty().removePrefix("www.") }.getOrNull().orEmpty()

        /** Coarse content classification from the response Content-Type (sniffing as fallback). */
        fun contentKind(contentType: String?, body: String): ContentKind {
            val ct = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            return when (ct) {
                "text/html", "application/xhtml+xml" -> ContentKind.Html
                "text/plain" -> ContentKind.PlainText
                "" -> {
                    val head = body.trimStart().take(512).lowercase()
                    if (head.startsWith("<!doctype html") || head.startsWith("<html")) ContentKind.Html
                    else ContentKind.PlainText
                }
                else -> ContentKind.Unsupported
            }
        }

        /** One page of [text] from char [offset]: (slice, truncated, next_offset). Page = one shown cap. */
        fun paginate(text: String, offset: Int): Triple<String, Boolean, Int?> {
            val start = offset.coerceIn(0, text.length)
            val end = minOf(start + MAX_READ_CHARS, text.length)
            val truncated = end < text.length
            return Triple(text.substring(start, end), truncated, if (truncated) end else null)
        }

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

        /** Host-capped page rendering; appends a truncation note when paging remains. */
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

    enum class ContentKind { Html, PlainText, Unsupported }
}
