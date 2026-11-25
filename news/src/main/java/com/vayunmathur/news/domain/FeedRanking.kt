package com.vayunmathur.news.domain

import kotlin.math.log2
import kotlin.math.pow

/** Feed order, persisted by the prefs owner. Search ignores this (relevance wins). */
enum class FeedSort(val label: String) {
    TOP("Top stories"),
    LATEST("Latest"),
}

/** One ranked representative plus how many other sources its server story carries. */
data class RankedArticle(
    val article: FeedArticle,
    /**
     * Extra distinct sources in the same server-grouped story
     * (0 = singleton or single-source story). Derived only from
     * actual server `storyId` membership — never from headline
     * similarity (there is no client-side grouping).
     */
    val alsoFromSources: Int = 0,
)

/**
 * Client-side feed ranking over the merged corpus. The server's
 * `news.json` stays newest-first; this is applied on device only, over
 * whatever category filter already narrowed the corpus. Search never
 * goes through here — it keeps its [NewsSearch] relevance ranking.
 *
 * ## Top stories formula (no popularity data exists, so on-device signals only)
 *
 * Score (per article, see [score]):
 * ```
 * ageHours = max(0, (now - publishedAtMillis) / 3_600_000)   // future stamps clamp to 0
 * score    = 100 * 0.5^(ageHours / 18)                       // 18 h half-life
 *          + 5 if imageUrl != null
 *          + 2 if keywords is non-empty
 * ```
 * So a fresh article scores ~100–107, one 18 h old ~50, 36 h old ~25,
 * 3 days old ~6 — day-old sinks but does not vanish. An unknown
 * timestamp (`publishedAtMillis <= 0`) scores 0 and sinks to the end.
 *
 * ## Stories (group before ranking — mechanically, by server id only)
 *
 * Stories come from [StoryGrouping]: articles sharing the exact
 * server `storyId` form one story; every article without one is a
 * singleton. Ranking only *orders* those stories — it never merges,
 * splits, or otherwise influences membership. Each story keeps one
 * representative: earliest `publishedAtMillis`, tie-broken by fuller
 * metadata (image, then more keywords, then URL); the representative
 * reports `distinct sources - 1` (actual server membership only) as
 * [RankedArticle.alsoFromSources].
 *
 * ## Top stories for stories ([rankStories] / [storyScore])
 * ```
 * ageHours = max(0, (now - story.latestMillis) / 3_600_000)
 * score    = 100 * 0.5^(ageHours / 18)            // recency of latest update
 *          + 5 if hero image present
 *          + 2 if any member has keywords
 *          + crossSourceBonus                     // see below, capped 30
 * crossSourceBonus = 0 if 1 source else min(30, 10 * log2(sourceCount))
 * ```
 * Cross-source breadth (counted only over actual server-story
 * membership) is an honest prominence signal (independent outlets
 * the server grouped into one story), folded in *alongside*
 * recency: 2 sources +10, 4 sources +20, 8+ +30. It breaks ties
 * between same-age stories and lifts a widely-covered day-old story
 * (50+20) above a single-source half-day story (~71 vs ~75 — close),
 * but a fresh single-source story (~100) still beats a 3-day-old
 * wire everywhere (~6+30). Latest sorts by [Story.latestMillis].
 *
 * ## Source diversity (interleave after scoring)
 *
 * Greedy fill in score order with a first-page cap: while the output
 * has fewer than [FIRST_PAGE_SIZE] (20) rows, one source may contribute
 * at most [FIRST_PAGE_PER_SOURCE] (3) of them; if every remaining
 * candidate is from a capped source the cap is relaxed for that pick
 * (a one-source corpus still fills the page). Past the first page the
 * cap lifts, but the same source never runs 3 times consecutively
 * while another source remains. Net effect: a high-volume wire
 * (BBC/G1 at ~100 items) can own at most 3 of the first 20 rows, and
 * the first screen always spans at least 7 sources when that many
 * have fresh stories.
 *
 * Latest is a plain newest-first sort of the same mechanical
 * stories (one row per server story / singleton, in either order).
 */
object FeedRanking {

    const val HALF_LIFE_HOURS = 18.0
    const val IMAGE_BONUS = 5.0
    const val KEYWORD_BONUS = 2.0
    const val FIRST_PAGE_SIZE = 20
    const val FIRST_PAGE_PER_SOURCE = 3
    /** Cap on the cross-source bonus in [storyScore]. */
    const val MAX_CROSS_SOURCE_BONUS = 30.0

    /** Rank [articles] for the feed; [nowMillis] is injected for tests. */
    fun rank(
        articles: List<FeedArticle>,
        sort: FeedSort,
        nowMillis: Long,
    ): List<RankedArticle> = rankStories(articles, sort, nowMillis).map {
        RankedArticle(it.representative, alsoFromSources = (it.sourceCount - 1).coerceAtLeast(0))
    }

    /** Group [articles] into stories and rank the stories for the feed. */
    fun rankStories(
        articles: List<FeedArticle>,
        sort: FeedSort,
        nowMillis: Long,
    ): List<Story> = rankGroupedStories(StoryGrouping.group(articles), sort, nowMillis)

    /** Rank already-grouped [stories] (grouping done once, ranking/filtering many times). */
    fun rankGroupedStories(
        stories: List<Story>,
        sort: FeedSort,
        nowMillis: Long,
    ): List<Story> {
        return when (sort) {
            FeedSort.LATEST -> stories.sortedWith(
                compareByDescending<Story> { it.latestMillis }
                    .thenByDescending { it.firstSeenMillis }
                    .thenBy { it.id },
            )
            FeedSort.TOP -> diversifyStories(stories, nowMillis)
        }
    }

    /** Top-stories score of a story (see KDoc formula). */
    fun storyScore(story: Story, nowMillis: Long): Double {
        if (story.latestMillis <= 0L) return 0.0
        val ageHours = ((nowMillis - story.latestMillis).coerceAtLeast(0L)) / 3_600_000.0
        var score = 100.0 * 0.5.pow(ageHours / HALF_LIFE_HOURS)
        if (story.heroImageUrl != null) score += IMAGE_BONUS
        if (story.articles.any { it.keywords.isNotEmpty() }) score += KEYWORD_BONUS
        if (story.sourceCount > 1) {
            score += minOf(MAX_CROSS_SOURCE_BONUS, 10.0 * log2(story.sourceCount.toDouble()))
        }
        return score
    }

    /** Ranking score of one article under Top stories (see KDoc formula). */
    fun score(article: FeedArticle, nowMillis: Long): Double {
        if (article.publishedAtMillis <= 0L) return 0.0
        val ageHours = ((nowMillis - article.publishedAtMillis).coerceAtLeast(0L)) / 3_600_000.0
        var score = 100.0 * 0.5.pow(ageHours / HALF_LIFE_HOURS)
        if (article.imageUrl != null) score += IMAGE_BONUS
        if (article.keywords.isNotEmpty()) score += KEYWORD_BONUS
        return score
    }

    private fun diversifyStories(stories: List<Story>, nowMillis: Long): List<Story> {
        val remaining = stories.sortedWith(
            compareByDescending<Story> { storyScore(it, nowMillis) }
                .thenByDescending { it.latestMillis }
                .thenBy { it.id },
        ).toMutableList()
        val out = mutableListOf<Story>()
        while (remaining.isNotEmpty()) {
            val pick = if (out.size < FIRST_PAGE_SIZE) {
                val perSource = out.groupingBy { it.representative.sourceId }.eachCount()
                remaining.firstOrNull { (perSource[it.representative.sourceId] ?: 0) < FIRST_PAGE_PER_SOURCE }
                    ?: remaining.first()
            } else {
                val tail = out.takeLast(2)
                if (tail.size == 2 && tail.all { it.representative.sourceId == tail[0].representative.sourceId }) {
                    remaining.firstOrNull { it.representative.sourceId != tail[0].representative.sourceId }
                        ?: remaining.first()
                } else {
                    remaining.first()
                }
            }
            out += pick
            remaining.remove(pick)
        }
        return out
    }
}
