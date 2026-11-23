package com.vayunmathur.news.domain

import kotlin.math.abs

/**
 * A **story**: one real-world event/development told by one or more
 * articles. The feed is a list of stories, not a flat article list.
 *
 * [id] is stable from the representative (earliest article, see
 * [StoryGrouping]): the normalised representative URL. It survives
 * re-clustering as long as the earliest telling stays in the cache,
 * which is exactly the wire-origin article. It is carried in
 * `Route.Story` — never in ViewModel selection state.
 *
 * [articles] are all members sorted earliest → latest (unknown
 * timestamps last, then URL), so a story page reads as a timeline of
 * how coverage developed. [representative] is `articles.first()`
 * when any timestamp is known, otherwise the deterministic first
 * member; [title] and the feed card headline are the representative's.
 */
data class Story(
    val id: String,
    val representative: FeedArticle,
    val articles: List<FeedArticle>,
) {
    /** Server canonical title if grouped server-side, else representative's. */
    val title: String
        get() = articles.firstNotNullOfOrNull { it.serverStoryTitle?.takeIf { t -> t.isNotBlank() } }
            ?: representative.title
    /** Server one-liner summary (generated, never extracted text), if any. */
    val oneLiner: String?
        get() = representative.storyOneLiner?.takeIf { it.isNotBlank() }
            ?: articles.firstNotNullOfOrNull { it.storyOneLiner?.takeIf { t -> t.isNotBlank() } }
    /** Server story id when the server grouped (some of) these articles. */
    val serverStoryId: String?
        get() = articles.firstNotNullOfOrNull { it.serverStoryId }
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
 * Deterministic, pure story clustering over a headline corpus.
 * No ML / embeddings (no model deps in this app): token/entity
 * overlap + keyword overlap + time proximity is the honest toolkit.
 *
 * ## Algorithm (inverted index, not O(n²))
 * Pairwise union-find over 500–2,500 articles is measured in tests
 * ([StoryGroupingTest]); naive O(n²) Jaccard at 2,500 is ~3.1M
 * pairs. Instead candidate pairs are generated from an inverted
 * index and only those pairs are scored:
 *  - exact normalised-title groups (hash map), and
 *  - postings for *discriminative* tokens: keyword tokens, entity
 *    tokens, and title tokens whose document frequency is
 *    ≤ [MAX_DOC_FREQ] (rare tokens). A posting longer than
 *    [MAX_POSTING] is skipped (too common to discriminate).
 * Expected candidates are O(n · avg posting length) — orders of
 * magnitude below n² — and every candidate is then judged by the
 * full merge predicate below, so indexing only loses pairs that
 * share no discriminative token at all (they could never pass the
 * entity/keyword gate of the relaxed rules, and the strict rules
 * need a near-identical title, which necessarily shares tokens).
 *
 * ## Merge predicate (pairwise, then single-linkage union-find)
 * Let `J` = Jaccard of stopword-filtered title token sets,
 * `sharedEntity` = shared entity tokens (capitalised-name tokens
 * when the headline is not all-Title-Case, plus keyword tokens),
 * `sharedKeyword` = shared normalised keyword strings,
 * `dt` = |Δ publishedAt| (unknown timestamps: `dt = 0` — no time
 * evidence, fall back to text only).
 * A pair merges when any rule fires (thresholds are `const`s so
 * they are tunable in one place):
 *  1. **Exact**: normalised titles equal and dt ≤ [EXACT_WINDOW_MS]
 *     (72h). Identical headlines 3 days apart are *not* merged by
 *     headline overlap alone — a recurring fixture/headline is a
 *     new story — but a chain A–B (≤72h), B–C (≤72h) still merges
 *     A,B,C via single linkage, so a developing story follows its
 *     updates across a longer total span.
 *  2. **Near-duplicate** (legacy bar): `J ≥ 0.85`, ≥4 tokens each,
 *     dt ≤ [NEAR_DUP_WINDOW_MS] (48h). Wire-copy rewrites.
 *  3. **Same event, rewritten**: `J ≥ 0.55` and
 *     (`sharedEntity ≥ 1` or `sharedKeyword ≥ 1`) and
 *     dt ≤ [EVENT_WINDOW_MS] (36h).
 *  4. **Update of a known event**: `J ≥ 0.40` and
 *     `sharedEntity ≥ 2` and dt ≤ [EVENT_WINDOW_MS].
 *  5. **Fast update chain**: `J ≥ 0.30`, `sharedEntity ≥ 1`,
 *     dt ≤ [UPDATE_WINDOW_MS] (12h) — a headline that evolves
 *     within hours around the same named people/places.
 * Server grouping (`FeedArticle.serverStoryId`, from full-text
 * server extraction) wins over all of the above: shared server ids
 * are pre-unioned, and two components carrying different server ids
 * are never merged, even transitively through an ungrouped article.
 * Extracted article text is never served to the client; only the
 * generated `story_one_liner` may accompany the assignment.
 * Different events with similar *generic* headlines ("Markets rally
 * on jobs data" vs "Markets rally on inflation data", J≈0.66 but
 * no shared entity/keyword) fire no rule: rules 3–5 all require a
 * shared entity/keyword, and rule 2 needs J ≥ 0.85. That gate is
 * the precision guard; the recall gain over headline-Jaccard-only
 * comes precisely from rules 3–5.
 * Single-linkage chaining is intentional (updates follow into the
 * story) and bounded by the per-pair windows: a chain can span
 * days only through ≤window steps.
 *
 * Representative: earliest `publishedAtMillis` (wire copy
 * originates once), tie-broken by image, more keywords, then URL —
 * the same rule the pre-story feed used, so story ids/titles are
 * stable against the old representative choice.
 */
object StoryGrouping {

    const val EXACT_WINDOW_MS = 72L * 3_600_000L
    const val NEAR_DUP_WINDOW_MS = 48L * 3_600_000L
    const val EVENT_WINDOW_MS = 36L * 3_600_000L
    const val UPDATE_WINDOW_MS = 12L * 3_600_000L
    const val NEAR_DUP_JACCARD = 0.85
    const val EVENT_JACCARD = 0.55
    const val UPDATE_JACCARD = 0.40
    const val CHAIN_JACCARD = 0.30
    const val MIN_TOKENS = 4
    /** Tokens occurring in more articles than this are not discriminative. */
    const val MAX_DOC_FREQ = 12
    /** Postings longer than this are skipped during candidate generation. */
    const val MAX_POSTING = 60

    private val STOPWORDS = setOf(
        "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by",
        "as", "is", "are", "was", "were", "be", "been", "has", "have", "had", "will", "would",
        "can", "could", "says", "said", "after", "before", "over", "under", "amid", "into",
        "from", "new", "live", "updates", "update", "latest",
    )

    fun group(articles: List<FeedArticle>): List<Story> {
        if (articles.isEmpty()) return emptyList()
        val feats = articles.map { Features.of(it) }
        val parent = IntArray(articles.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val n = parent[c]; parent[c] = r; c = n }
            return r
        }

        // Per-root server-id sets (O(1) bridge guard, not a corpus scan per merge).
        val rootServerIds = HashMap<Int, Set<String>>()
        articles.forEachIndexed { i, art ->
            rootServerIds[i] = art.serverStoryId?.takeIf { it.isNotBlank() }?.let { setOf(it) } ?: emptySet()
        }
        fun unionTracked(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra == rb) return
            val root = minOf(ra, rb); val other = maxOf(ra, rb)
            parent[other] = root
            rootServerIds[root] = (rootServerIds[ra].orEmpty() + rootServerIds[rb].orEmpty())
            rootServerIds.remove(other)
        }
        // Server grouping wins: pre-union every shared serverStoryId.
        articles.indices.groupBy { articles[it].serverStoryId }.forEach { (sid, idx) ->
            if (!sid.isNullOrBlank() && idx.size > 1) {
                for (k in 1 until idx.size) unionTracked(idx[0], idx[k])
            }
        }
        val df = HashMap<String, Int>()
        feats.forEach { f -> f.contentTokens.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val candidates = candidatePairs(feats, df).sortedWith(compareBy({ it.first }, { it.second }))
        for ((i, j) in candidates) {
            if (!shouldMerge(feats[i], feats[j])) continue
            // Never merge components carrying different server stories,
            // even transitively: an ungrouped article may join ONE
            // server story, it must not bridge two of them.
            val ri = find(i); val rj = find(j)
            if (ri == rj) continue
            val idsI = rootServerIds[ri].orEmpty(); val idsJ = rootServerIds[rj].orEmpty()
            if (idsI.isNotEmpty() && idsJ.isNotEmpty() && idsI != idsJ) continue
            unionTracked(i, j)
        }
        return articles.indices.groupBy { find(it) }.values.map { idx ->
            val members = idx.map { articles[it] }.sortedWith(
                compareBy<FeedArticle> { if (it.publishedAtMillis > 0) it.publishedAtMillis else Long.MAX_VALUE }
                    .thenBy { it.url },
            )
            val representative = members.minWithOrNull(
                compareBy<FeedArticle> { if (it.publishedAtMillis > 0) it.publishedAtMillis else Long.MAX_VALUE }
                    .thenByDescending { it.imageUrl != null }
                    .thenByDescending { it.keywords.size }
                    .thenBy { it.url },
            ) ?: members.first()
            // Server-grouped stories keep the server id (prefixed so
            // it can never collide with a client representative URL).
            val serverId = members.firstNotNullOfOrNull { it.serverStoryId?.takeIf { s -> s.isNotBlank() } }
            Story(
                id = if (serverId != null) "srv:$serverId" else FeedMerger.normaliseUrl(representative.url),
                representative = representative,
                articles = members,
            )
        }
    }

    /** Candidate pairs from exact-title groups + discriminative-token postings. */
    private fun candidatePairs(feats: List<Features>, df: Map<String, Int>): Set<Pair<Int, Int>> {
        val pairs = HashSet<Pair<Int, Int>>()
        fun add(a: Int, b: Int) { if (a != b) pairs += minOf(a, b) to maxOf(a, b) }
        // Exact normalised titles.
        feats.indices.groupBy { feats[it].normalised }.forEach { (norm, idx) ->
            if (norm.isNotEmpty()) for (a in idx) for (b in idx) add(a, b)
        }
        val postings = HashMap<String, MutableList<Int>>()
        feats.forEachIndexed { i, f ->
            val keys = HashSet<String>()
            f.contentTokens.filterTo(keys) { (df[it] ?: 0) <= MAX_DOC_FREQ }
            keys += f.entityTokens.map { "e:$it" }
            keys += f.keywordKeys.map { "k:$it" }
            keys.forEach { k -> postings.getOrPut(k) { mutableListOf() } += i }
        }
        postings.values.forEach { posting ->
            if (posting.size in 2..MAX_POSTING) {
                for (a in posting) for (b in posting) add(a, b)
            }
        }
        return pairs
    }

    internal fun shouldMerge(a: Features, b: Features): Boolean {
        if (a.normalised.isEmpty() || b.normalised.isEmpty()) return false
        val dt = if (a.published > 0 && b.published > 0) abs(a.published - b.published) else 0L
        if (a.normalised == b.normalised) return dt <= EXACT_WINDOW_MS
        val unionSize = (a.contentTokens union b.contentTokens).size
        if (unionSize == 0) return false
        val shared = a.contentTokens intersect b.contentTokens
        val j = shared.size.toDouble() / unionSize
        val sharedEntity = (a.entityTokens intersect b.entityTokens).size
        val sharedKeyword = (a.keywordKeys intersect b.keywordKeys).size
        if (a.contentTokens.size >= MIN_TOKENS && b.contentTokens.size >= MIN_TOKENS &&
            j >= NEAR_DUP_JACCARD && dt <= NEAR_DUP_WINDOW_MS
        ) return true
        // Relaxed rules require an entity/keyword gate: rare-token
        // overlap alone is not enough (generic headlines share it).
        if (j >= EVENT_JACCARD && (sharedEntity >= 1 || sharedKeyword >= 1) && dt <= EVENT_WINDOW_MS) return true
        if (j >= UPDATE_JACCARD && sharedEntity >= 2 && dt <= EVENT_WINDOW_MS) return true
        if (j >= CHAIN_JACCARD && sharedEntity >= 1 && dt <= UPDATE_WINDOW_MS) return true
        return false
    }

    /** Precomputed per-article comparison features. */
    internal data class Features(
        val normalised: String,
        val contentTokens: Set<String>,
        val entityTokens: Set<String>,
        val keywordKeys: Set<String>,
        val published: Long,
    ) {
        companion object {
            fun of(article: FeedArticle): Features {
                val normalised = FeedRanking.normaliseTitle(article.title)
                val tokens = if (normalised.isEmpty()) emptySet() else normalised.split(' ').toSet()
                val content = tokens - STOPWORDS
                val keywordKeys = article.keywords.map { FeedRanking.normaliseTitle(it) }.filter { it.isNotEmpty() }.toSet()
                val keywordTokens = keywordKeys.flatMap { it.split(' ') }.toSet() - STOPWORDS
                return Features(normalised, content, entityTokens(article.title) + keywordTokens, keywordKeys, article.publishedAtMillis)
            }

            /**
             * Capitalised name tokens from the raw headline. When the
             * headline is all Title Case (every word capitalised) caps
             * carry no signal, so only keyword tokens (added by caller)
             * count as entities.
             */
            private fun entityTokens(title: String): Set<String> {
                val words = title.split(Regex("\\s+")).map { it.trim(',', '.', ':', ';', '"', '“', '”', '(', ')') }
                    .filter { it.isNotEmpty() }
                if (words.size < 2) return emptySet()
                // Sentence-initial capital is not a name; drop word 0.
                val rest = words.drop(1)
                val capitalised = rest.filter { it.first().isUpperCase() }
                if (capitalised.size >= rest.size.coerceAtLeast(1)) return emptySet() // Title Case everywhere
                return capitalised.map { FeedRanking.normaliseTitle(it) }.filter { it.length >= 3 }.toSet() - STOPWORDS
            }
        }
    }
}

/**
 * Stories containing at least one of [hitUrlsInRankOrder] (search hit
 * article URLs in relevance order), in the order their best hit
 * appears — an article match surfaces its whole story.
 */
fun List<Story>.orderedBySearchHits(hitUrlsInRankOrder: List<String>): List<Story> {
    if (hitUrlsInRankOrder.isEmpty()) return emptyList()
    val byUrl = HashMap<String, Story>()
    forEach { story -> story.articles.forEach { byUrl[it.url] = story } }
    val seen = HashSet<String>()
    return hitUrlsInRankOrder.mapNotNull { byUrl[it] }.filter { seen.add(it.id) }
}
