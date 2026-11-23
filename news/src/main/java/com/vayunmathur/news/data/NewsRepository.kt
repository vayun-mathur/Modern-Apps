package com.vayunmathur.news.data

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.room.RoomRepository
import com.vayunmathur.news.domain.ArticleBlock
import com.vayunmathur.news.domain.ArticleExtractor
import com.vayunmathur.news.domain.ExtractedArticle
import com.vayunmathur.news.domain.FeedMerger
import com.vayunmathur.news.domain.NewsSource
import com.vayunmathur.news.domain.SitemapParser
import com.vayunmathur.news.domain.SourceCatalog
import com.vayunmathur.news.network.Networking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Owns the Room cache and all feed/article fetching. The [fetch] seam
 * defaults to [Networking] and is replaced by a fake in unit tests, so
 * no test touches the network. Bodies are extracted on device only.
 */
class NewsRepository internal constructor(
    context: Context,
    private val fetch: suspend (String) -> String,
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
     * Fetches every enabled source (sitemap indexes are followed one level,
     * recursively through this same function), merges and caches the result.
     * A failing source never fails the refresh. Returns sources that failed.
     */
    suspend fun refresh(sources: List<NewsSource>): List<String> = coroutineScope {
        val results = sources.map { source ->
            async {
                runCatching { fetchFeed(source, depth = 0) }
                    .fold(
                        onSuccess = { it to null },
                        onFailure = { emptyList<com.vayunmathur.news.domain.FeedArticle>() to source.name },
                    )
            }
        }.awaitAll()
        val failed = results.mapNotNull { it.second }
        val merged = FeedMerger.merge(results.map { it.first })
        val now = System.currentTimeMillis()
        if (merged.isNotEmpty()) {
            // Preserve bookmark flags already in the cache.
            val rows = merged.map { article ->
                val existing = dao.article(article.url)
                CachedArticle(
                    url = article.url, sourceId = article.sourceId, title = article.title,
                    publishedAtMillis = article.publishedAtMillis, imageUrl = article.imageUrl,
                    keywords = article.keywords.joinToString(","),
                    fetchedAtMillis = now, bookmarked = existing?.bookmarked ?: false,
                )
            }
            dao.upsertArticles(rows)
            dao.pruneArticles(keep = 500)
        }
        failed
    }

    private suspend fun fetchFeed(
        source: NewsSource,
        depth: Int,
    ): List<com.vayunmathur.news.domain.FeedArticle> {
        val parsed = SitemapParser.parse(fetch(source.feedUrl), source.id)
        if (parsed.childSitemaps.isEmpty() || depth >= MAX_INDEX_DEPTH) return parsed.articles
        // Indexes list newest child first; the first few carry today's news.
        return coroutineScope {
            parsed.childSitemaps.take(MAX_INDEX_CHILDREN).map { child ->
                async {
                    runCatching {
                        val childParsed = SitemapParser.parse(fetch(child), source.id)
                        if (childParsed.childSitemaps.isNotEmpty() && depth + 1 < MAX_INDEX_DEPTH) {
                            childParsed.childSitemaps.take(MAX_INDEX_CHILDREN - 1).flatMap { grandchild ->
                                runCatching {
                                    SitemapParser.parse(fetch(grandchild), source.id).articles
                                }.getOrElse {
                                    Log.error(TAG, "feed fetch failed")
                                    emptyList()
                                }
                            }
                        } else {
                            childParsed.articles
                        }
                    }.getOrElse {
                        Log.error(TAG, "feed fetch failed")
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
    }

    /** Cached body if present, otherwise fetch + extract + cache. Throws on fetch failure with no cache. */
    suspend fun articleBody(url: String): ExtractedArticle {
        dao.body(url)?.let { return it.toExtracted(json) }
        val extracted = ArticleExtractor.extract(fetch(url), url)
        dao.upsertBody(extracted.toCached(json))
        return extracted
    }

    companion object {
        private const val TAG = "NewsRepository"
        private const val MAX_INDEX_DEPTH = 2
        private const val MAX_INDEX_CHILDREN = 3

        @Volatile
        private var instance: NewsRepository? = null

        fun get(context: Context): NewsRepository =
            instance ?: synchronized(this) {
                instance ?: NewsRepository(context.applicationContext).also { instance = it }
            }
    }
}

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
