package com.vayunmathur.news.domain

/**
 * A **story**: the articles the *server* grouped under one
 * `story_id`, or a single article when the server gave it no
 * `story_id`. The feed is a list of stories, not a flat article list.
 *
 * The client makes **no grouping decisions**: [StoryGrouping] is
 * purely mechanical (see its KDoc). It never compares headlines,
 * entities, keywords or timestamps to decide membership, so two
 * articles the server did not group are never shown as one story,
 * however similar they look.
 *
 * [id] is `srv:<serverStoryId>` for a server-grouped story, else the
 * normalised URL of its sole article. It is carried in
 * `Route.Story` — never in ViewModel selection state.
 *
 * [articles] are all members sorted earliest → latest (unknown
 * timestamps last, then URL), so a story page reads as a timeline of
 * how coverage developed. [representative] is the earliest member
 * (tie-broken by image, more keywords, then URL); [title] is the
 * server-provided `story_title` verbatim when present, else the
 * representative's own headline.
 */
data class Story(
    val id: String,
    val representative: FeedArticle,
    val articles: List<FeedArticle>,
) {
    /** Server canonical title verbatim if present, else representative's. */
    val title: String
        get() = articles.firstNotNullOfOrNull { it.serverStoryTitle?.takeIf { t -> t.isNotBlank() } }
            ?: representative.title
    /** Server one-liner summary (generated, never extracted text), if any. */
    val oneLiner: String?
        get() = articles.firstNotNullOfOrNull { it.storyOneLiner?.takeIf { t -> t.isNotBlank() } }
    /** Server story id of this story, or null for a singleton. */
    val serverStoryId: String?
        get() = articles.firstNotNullOfOrNull { it.serverStoryId?.takeIf { s -> s.isNotBlank() } }
    /** Distinct source ids carrying this story, sorted. */
    val sources: List<String> get() = articles.map { it.sourceId }.distinct().sorted()
    /** Distinct source display names (catalog name, else id), sorted. */
    val sourceNames: List<String>
        get() = sources.map { SourceCatalog.byId(it)?.name ?: it }.sorted()
    val sourceCount: Int get() = sources.size
    val articleCount: Int get() = articles.size
    /** Earliest known publication, or 0 when every member is unstamped. */
    val firstSeenMillis: Long
        get() = articles.map { it.publishedAtMillis }.filter { it > 0 }.minOrNull() ?: 0L
    /** Latest known publication (the feed's "last updated"), or 0. */
    val latestMillis: Long
        get() = articles.map { it.publishedAtMillis }.maxOrNull() ?: 0L
    /** Union of member sources' category tags, taxonomy order. */
    val categories: List<NewsCategory>
        get() = NewsCategory.entries.filter { cat -> articles.any { cat in SourceCatalog.categoriesOf(it.sourceId) } }
    /** Distinct BCP-47 languages of members, sorted. */
    val languages: List<String>
        get() = articles.mapNotNull { it.language }.distinct().sorted()
    /**
     * Best hero image: the latest article that carries an image
     * (freshest visual wins), else null. (The representative is the
     * earliest telling; its image is often a stale wire thumbnail.)
     */
    val heroImageUrl: String?
        get() = articles.lastOrNull { it.imageUrl != null }?.imageUrl

    /** Whether any member's source matches [category] (null = All). */
    fun matchesCategory(category: NewsCategory?): Boolean =
        category == null || articles.any { SourceCatalog.matchesCategory(it.sourceId, category) }
}

/**
 * Mechanical story construction — the *only* grouping rule in the app:
 *
 *  - Articles sharing the exact same non-blank server `storyId`
 *    (`FeedArticle.serverStoryId`, from `story_id` in `news.json`)
 *    form one story, in that key only.
 *  - Every article with a null/blank `storyId` is its own
 *    single-article (singleton) story. That includes every article
 *    from the fallback on-device sitemap/RSS crawl, which carries no
 *    `storyId` at all.
 *
 * Nothing else influences membership: not headline similarity, not
 * entities or keywords, not timestamps, and not ranking (ranking in
 * [FeedRanking] only *orders* the stories built here). An ungrouped
 * article is never merged into a server story, and two different
 * server story ids are never merged — even when their headlines are
 * identical. Server-provided `story_title` / `story_one_liner` are
 * surfaced verbatim via [Story.title] / [Story.oneLiner]; extracted
 * article text is never served to or consumed by the client.
 *
 * Stories are returned in first-appearance order of their key in
 * [articles]; callers that display them re-order with [FeedRanking].
 */
object StoryGrouping {

    fun group(articles: List<FeedArticle>): List<Story> {
        if (articles.isEmpty()) return emptyList()
        // Exact key only: non-blank server storyId, else a unique
        // singleton key per article (its index — never shared).
        val byKey = LinkedHashMap<String, MutableList<FeedArticle>>()
        articles.forEachIndexed { index, article ->
            val serverId = article.serverStoryId?.takeIf { it.isNotBlank() }
            val key = if (serverId != null) "srv:$serverId" else "one:$index"
            byKey.getOrPut(key) { mutableListOf() } += article
        }
        return byKey.entries.map { (key, membersUnsorted) ->
            val members = membersUnsorted.sortedWith(
                compareBy<FeedArticle> { if (it.publishedAtMillis > 0) it.publishedAtMillis else Long.MAX_VALUE }
                    .thenBy { it.url },
            )
            val representative = members.minWithOrNull(
                compareBy<FeedArticle> { if (it.publishedAtMillis > 0) it.publishedAtMillis else Long.MAX_VALUE }
                    .thenByDescending { it.imageUrl != null }
                    .thenByDescending { it.keywords.size }
                    .thenBy { it.url },
            ) ?: members.first()
            Story(
                id = if (key.startsWith("srv:")) key else FeedMerger.normaliseUrl(representative.url),
                representative = representative,
                articles = members,
            )
        }
    }
}

/**
 * Stories containing at least one of [hitUrlsInRankOrder] (search hit
 * article URLs in relevance order), in the order their best hit
 * appears — an article match surfaces its whole (server-grouped or
 * singleton) story.
 */
fun List<Story>.orderedBySearchHits(hitUrlsInRankOrder: List<String>): List<Story> {
    if (hitUrlsInRankOrder.isEmpty()) return emptyList()
    val byUrl = HashMap<String, Story>()
    forEach { story -> story.articles.forEach { byUrl[it.url] = story } }
    val seen = HashSet<String>()
    return hitUrlsInRankOrder.mapNotNull { byUrl[it] }.filter { seen.add(it.id) }
}
