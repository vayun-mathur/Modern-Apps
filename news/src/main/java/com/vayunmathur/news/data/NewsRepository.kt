package com.vayunmathur.news.data

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.room.RoomRepository
import com.vayunmathur.news.domain.ArticleBlock
import com.vayunmathur.news.domain.ArticleExtractor
import com.vayunmathur.news.domain.ExtractedArticle
import com.vayunmathur.news.domain.FeedArticle
import com.vayunmathur.news.domain.FeedMerger
import com.vayunmathur.news.domain.NewsSearch
import com.vayunmathur.news.domain.NewsSource
import com.vayunmathur.news.domain.ServerFeedParser
import com.vayunmathur.news.domain.SitemapParser
import com.vayunmathur.news.domain.SourceCatalog
import com.vayunmathur.news.network.Networking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Owns the Room cache and all feed/article fetching. The [fetch] seam
 * defaults to [Networking] and is replaced by a fake in unit tests, so
 * no test touches the network. Bodies are extracted on device only.
 *
 * Refresh topology (fixed 2026-11-23, was the ANR / "stuck feed" bug):
 * sources fetch in parallel, each on [feedDispatcher] (never Main —
 * parsing a ~996-URL sitemap on Main burned 22s CPU in regex and
 * ANR'd), each bounded by [sourceTimeoutMs], and each source's
 * articles are upserted to Room the moment that source lands. The
 * feed is the Room flow, so cached/partial articles are visible
 * immediately; one hanging or failing source can neither block the
 * others' publication nor hold the refresh open past its timeout.
 */
class NewsRepository internal constructor(
    context: Context,
    private val fetch: suspend (String) -> String,
    private val feedDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val sourceTimeoutMs: Long = SOURCE_TIMEOUT_MS,
) : RoomRepository<NewsDatabase>(context, NewsDatabase::class, DB_NAME) {

    constructor(context: Context) : this(context, Networking::fetchText)

    private val dao get() = db.newsDao()
    private val json = Json { ignoreUnknownKeys = true }

    val articles: Flow<List<CachedArticle>> get() = dao.observeArticles()
    val bookmarked: Flow<List<CachedArticle>> get() = dao.observeBookmarked()

    suspend fun isBookmarked(url: String): Boolean = dao.article(url)?.bookmarked == true

    suspend fun setBookmarked(url: String, bookmarked: Boolean) =
        dao.setBookmarked(url, bookmarked)

    /**
     * Primary path: one fetch of the server's `news.json`, merged into
     * Room exactly like a crawl result. Every source the server carries
     * is always included — there is no client-side enabled-source
     * filter; categories and search are the filters. [sources] only
     * drives the fallback crawl.
     *
     * Fallback decision: if the server fetch throws, times out, or
     * parses to zero articles, refresh falls back to the on-device
     * per-source sitemap/RSS crawl of [sources]
     * ([refreshFromSources]). The cached Room feed stays visible
     * throughout either way. Returns failed source names from whichever
     * path ran; a successful server fetch (or a fully successful
     * fallback) returns an empty list.
     */
    suspend fun refresh(sources: List<NewsSource>): List<String> {
        val serverArticles = fetchServerFeed()
        if (serverArticles != null) {
            val capped = FeedMerger.merge(listOf(serverArticles), limit = MAX_CACHED_ARTICLES)
            if (capped.isNotEmpty()) publish(capped)
            dao.pruneArticles(keep = MAX_CACHED_ARTICLES)
            Log.dev(TAG, "refresh server feed landed articles=${capped.size}")
            return emptyList()
        }
        Log.dev(TAG, "refresh server feed unavailable; falling back to on-device source crawl")
        return refreshFromSources(sources)
    }

    /** Server feed articles, or null on transport failure / timeout / empty parse. */
    private suspend fun fetchServerFeed(): List<FeedArticle>? {
        val articles = try {
            withTimeoutOrNull(sourceTimeoutMs) {
                withContext(feedDispatcher) { ServerFeedParser.parse(fetch(Networking.SERVER_FEED_URL)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.dev(TAG, "server feed failed ${e.javaClass.simpleName}")
            null
        }
        return articles?.takeIf { it.isNotEmpty() }
    }

    /**
     * Fallback crawl: fetches every source in parallel; each
     * source publishes to Room as soon as it lands. Returns the names
     * of sources that failed or timed out (a failing source never
     * fails the refresh).
     */
    suspend fun refreshFromSources(sources: List<NewsSource>): List<String> = coroutineScope {
        val failed = sources.map { source ->
            async { fetchAndPublish(source) }
        }.awaitAll().filterNotNull()
        if (sources.isNotEmpty()) dao.pruneArticles(keep = MAX_CACHED_ARTICLES)
        failed
    }

    /**
     * On-device search over the local corpus only (no server search):
     * titles, source names, sitemap keywords, and the full text of
     * already-downloaded bodies. Ranked by [NewsSearch] (title hits
     * first, then source/keyword, then body-only); see its ceiling doc.
     */
    suspend fun search(query: String): List<CachedArticle> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        if (terms.isEmpty()) return emptyList()
        val stubsByUrl = LinkedHashMap<String, CachedArticle>()
        val bodyHits = mutableSetOf<String>()
        for (term in terms) {
            dao.searchStubHits(term).forEach { stubsByUrl[it.url] = it }
            bodyHits += dao.bodyHitUrls(term)
        }
        // Source-name hits: resolve matching catalog names/ids, then pull their stubs.
        val matchingSourceIds = SourceCatalog.sources
            .filter { source -> terms.any { source.name.lowercase().contains(it) || source.id.contains(it) } }
            .map { it.id }
        if (matchingSourceIds.isNotEmpty()) {
            dao.articlesForSources(matchingSourceIds).forEach { stubsByUrl[it.url] = it }
        }
        // Body-only hits may be stubs the title/keyword queries missed.
        val missingBodyUrls = bodyHits.filter { it !in stubsByUrl }
        if (missingBodyUrls.isNotEmpty()) {
            dao.articlesByUrls(missingBodyUrls).forEach { stubsByUrl[it.url] = it }
        }
        val candidates = stubsByUrl.values.map { article ->
            NewsSearch.Candidate(
                article = article.toFeedArticle(),
                sourceName = SourceCatalog.byId(article.sourceId)?.name ?: article.sourceId,
                bodyHit = article.url in bodyHits,
            )
        }
        val rankedUrls = NewsSearch.rank(query, candidates).map { it.url }
        val byUrl = stubsByUrl.toMap()
        return rankedUrls.mapNotNull { byUrl[it] }
    }

    /** Fetches + publishes one source; returns its name on failure/timeout, else null. */
    private suspend fun fetchAndPublish(source: NewsSource): String? {
        val startedNanos = System.nanoTime()
        Log.dev(TAG, "refresh source=${source.id} started")
        val articles = try {
            withTimeoutOrNull(sourceTimeoutMs) {
                withContext(feedDispatcher) { fetchFeed(source.feedUrl, source.id, depth = 0) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000
            Log.dev(TAG, "refresh source=${source.id} failed ${e.javaClass.simpleName} after ${elapsedMs}ms")
            return source.name
        }
        val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000
        if (articles == null) {
            Log.dev(TAG, "refresh source=${source.id} failed timeout after ${elapsedMs}ms")
            return source.name
        }
        val capped = FeedMerger.merge(listOf(articles), limit = MAX_ARTICLES_PER_SOURCE)
        if (capped.isNotEmpty()) publish(capped)
        val totalMs = (System.nanoTime() - startedNanos) / 1_000_000
        Log.dev(TAG, "refresh source=${source.id} landed articles=${capped.size} in ${totalMs}ms")
        return null
    }

    /** Upserts one landed source's articles, preserving existing bookmark flags. */
    private suspend fun publish(articles: List<FeedArticle>) {
        val now = System.currentTimeMillis()
        val bookmarked = dao.bookmarkedUrls(articles.map { it.url }).toSet()
        val rows = articles.map { article ->
            CachedArticle(
                url = article.url, sourceId = article.sourceId, title = article.title,
                publishedAtMillis = article.publishedAtMillis, imageUrl = article.imageUrl,
                keywords = article.keywords.joinToString(","),
                fetchedAtMillis = now, bookmarked = article.url in bookmarked,
                storyId = article.serverStoryId, storyOneLiner = article.storyOneLiner,
                storyTitle = article.serverStoryTitle,
            )
        }
        dao.upsertArticles(rows)
    }

    /**
     * Fetches one feed document and, for a sitemap index, its newest
     * [MAX_INDEX_CHILDREN] children (in parallel, recursively, at most
     * [MAX_INDEX_DEPTH] levels deep). Runs in the caller's context —
     * [fetchAndPublish] confines it to [feedDispatcher].
     */
    private suspend fun fetchFeed(url: String, sourceId: String, depth: Int): List<FeedArticle> {
        val parsed = SitemapParser.parse(fetch(url), sourceId)
        if (parsed.childSitemaps.isEmpty() || depth >= MAX_INDEX_DEPTH) return parsed.articles
        // Indexes list newest child first; the first few carry today's news.
        return coroutineScope {
            parsed.childSitemaps.take(MAX_INDEX_CHILDREN).map { child ->
                async {
                    runCatching { fetchFeed(child, sourceId, depth + 1) }
                        .getOrElse {
                            if (it is CancellationException) throw it
                            Log.dev(TAG, "refresh source=$sourceId child fetch failed url=$child")
                            emptyList()
                        }
                }
            }.awaitAll().flatten()
        }
    }

    /** Cached body if present, otherwise fetch + extract + cache. Throws on fetch failure with no cache. */
    suspend fun articleBody(url: String): ExtractedArticle {
        dao.body(url)?.let { return it.toExtracted(json) }
        // Extraction is CPU-heavy HTML work: never on the caller's (Main) dispatcher.
        val extracted = withContext(feedDispatcher) { ArticleExtractor.extract(fetch(url), url) }
        dao.upsertBody(extracted.toCached(json))
        return extracted
    }

    companion object {
        private const val TAG = "NewsRepository"

        /** Overall per-source budget: fetch + follow index + parse + publish. */
        const val SOURCE_TIMEOUT_MS = 20_000L
        private const val MAX_INDEX_DEPTH = 2
        private const val MAX_INDEX_CHILDREN = 3
        private const val MAX_ARTICLES_PER_SOURCE = 200
        private const val MAX_CACHED_ARTICLES = 500

        @Volatile
        private var instance: NewsRepository? = null

        fun get(context: Context): NewsRepository =
            instance ?: synchronized(this) {
                instance ?: NewsRepository(context.applicationContext).also { instance = it }
            }
    }
}

internal fun CachedArticle.toFeedArticle(): FeedArticle = FeedArticle(
    url = url, sourceId = sourceId, title = title, publishedAtMillis = publishedAtMillis,
    imageUrl = imageUrl,
    keywords = keywords.split(",").map { it.trim() }.filter { it.isNotEmpty() },
    language = SourceCatalog.byId(sourceId)?.language,
    serverStoryId = storyId, storyOneLiner = storyOneLiner, serverStoryTitle = storyTitle,
)

internal fun ExtractedArticle.toCached(json: Json): CachedBody = CachedBody(
    url = url, title = title, byline = byline, publishedAtMillis = publishedAtMillis,
    heroImageUrl = heroImageUrl,
    blocksJson = json.encodeToString(ListSerializer(ArticleBlock.serializer()), blocks),
    fetchedAtMillis = System.currentTimeMillis(),
)

internal fun CachedBody.toExtracted(json: Json): ExtractedArticle = ExtractedArticle(
    url = url, title = title, byline = byline, publishedAtMillis = publishedAtMillis,
    heroImageUrl = heroImageUrl,
    blocks = runCatching {
        json.decodeFromString(ListSerializer(ArticleBlock.serializer()), blocksJson)
    }.getOrDefault(emptyList()),
)
