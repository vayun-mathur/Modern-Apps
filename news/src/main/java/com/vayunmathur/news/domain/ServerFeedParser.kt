package com.vayunmathur.news.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Parser for the location_share_server `GET /news.json` contract:
 *
 * ```
 * { "generated_at": ..., "articles": [
 *     { "title": ..., "url": ..., "source": ..., "published_at": ...,
 *       "image_url": ..., "metered": false, "language": "en" } ] }
 * ```
 *
 * Contract assumptions (flagged for reconciliation with the server agent):
 * - `source` is a [SourceCatalog] id (`bbc`) or its exact display name
 *   (`BBC News`); both resolve to the catalog id. Anything else is kept
 *   verbatim as the sourceId — such articles are then dropped by the
 *   kept and shown under "All" only (no category), never re-attributed
 *   to a different source.
 * - `published_at` is an ISO-8601/RFC-1123 string (parsed by
 *   [DateParsing]) or epoch seconds/millis as a JSON number.
 * - `image_url` may be absent/null; `metered` may be absent (defaults
 *   false). `metered` is carried on [FeedArticle] but not persisted:
 *   the catalog already badges metered sources.
 * - `language` is a BCP-47 tag stated per article by the server (its
 *   per-article refinement of the source default); when absent it
 *   defaults from [SourceCatalog] for the resolved source.
 * - There is no category field. Category is derived client-side only:
 *   the resolved catalog id maps to [NewsSource.categories] in
 *   [SourceCatalog] (see [NewsCategory]), and the feed/search filters
 *   apply that mapping to the article's `sourceId`. Unresolved ids have
 *   no category and match the feed's "All" filter only.
 * - Story grouping (server-side, full-text signals): `story_id`
 *   (same for all articles of one story; server assignment wins over
 *   client clustering), `story_one_liner` (generated one-line summary
 *   — the only server text the client may display; extracted article
 *   text is NEVER served), and optional `story_title`. All optional:
 *   articles without them are client-clustered (fallback crawl path
 *   produces no story fields at all). Alias keys `story_oneliner` /
 *   `story_summary` are also accepted for the one-liner until the
 *   server schema freezes.
 *
 * Pure and non-throwing: malformed documents/articles yield an empty or
 * partial list, which the repository treats as server-feed failure and
 * falls back to the on-device sitemap crawl.
 */
object ServerFeedParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<FeedArticle> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val articles: JsonArray = root["articles"]?.let {
            runCatching { it.jsonArray }.getOrNull()
        } ?: return emptyList()
        return articles.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            parseArticle(obj)
        }
    }

    private fun parseArticle(obj: JsonObject): FeedArticle? {
        val url = obj.string("url")?.takeIf { it.startsWith("http") } ?: return null
        val title = obj.string("title")?.takeIf { it.isNotBlank() } ?: return null
        val sourceRaw = obj.string("source")?.takeIf { it.isNotBlank() } ?: return null
        val sourceId = resolveSourceId(sourceRaw)
        return FeedArticle(
            url = url,
            sourceId = sourceId,
            title = title,
            publishedAtMillis = parsePublishedAt(obj["published_at"]),
            imageUrl = obj.string("image_url"),
            metered = (obj["metered"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            language = obj.string("language") ?: SourceCatalog.byId(sourceId)?.language,
            serverStoryId = obj.string("story_id"),
            storyOneLiner = obj.string("story_one_liner") ?: obj.string("story_oneliner")
                ?: obj.string("story_summary"),
            serverStoryTitle = obj.string("story_title"),
        )
    }

    /** Catalog id for a catalog id or exact catalog name; otherwise [raw] verbatim. */
    fun resolveSourceId(raw: String): String {
        val trimmed = raw.trim()
        SourceCatalog.byId(trimmed)?.let { return it.id }
        return SourceCatalog.sources.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.id ?: trimmed
    }

    private fun parsePublishedAt(element: kotlinx.serialization.json.JsonElement?): Long {
        val primitive = element as? JsonPrimitive ?: return 0L
        if (primitive.isString) return DateParsing.parseMillis(primitive.contentOrNull) ?: 0L
        val value = primitive.contentOrNull?.toLongOrNull() ?: return 0L
        // < 10^10 is epoch seconds (year 2286 in millis terms); else millis.
        return if (value in 1 until 10_000_000_000L) value * 1_000L else value
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
