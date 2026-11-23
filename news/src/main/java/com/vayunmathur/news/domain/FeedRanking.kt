package com.vayunmathur.news.domain

import kotlin.math.pow

/** Feed order, persisted by the prefs owner. Search ignores this (relevance wins). */
enum class FeedSort(val label: String) {
    TOP("Top stories"),
    LATEST("Latest"),
}

/** One ranked representative plus how many near-duplicate sources it collapsed. */
data class RankedArticle(
    val article: FeedArticle,
    /** Extra sources carrying the same story (0 = carried by one source only). */
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
 * Score (per article, before clustering):
 * ```
 * ageHours = max(0, (now - publishedAtMillis) / 3_600_000)   // future stamps clamp to 0
 * score    = 100 * 0.5^(ageHours / 18)                       // 18 h half-life
 *          + 5 if imageUrl != null
 *          + 2 if keywords is non-empty
 * ```
 * So a fresh story scores ~100–107, a story 18 h old ~50, 36 h old ~25,
 * 3 days old ~6 — day-old sinks but does not vanish. An unknown
 * timestamp (`publishedAtMillis <= 0`) scores 0 and sinks to the end.
 * Ties break on newer `publishedAtMillis`, then URL, so the order is
 * total and deterministic.
 *
 * ## Story clustering (collapse before ranking)
 *
 * Titles are normalised (lower-case, punctuation dropped, whitespace
 * collapsed, a leading "source:" / "source -" prefix stripped) and
 * grouped: exact normalised-title matches always collapse, and two
 * titles also collapse when their token sets have Jaccard similarity
 * >= 0.85 and both have at least 4 tokens (union-find over the title
 * list; single-linkage, so A~B and B~C merge one story). Cluster size
 * is capped at [MAX_CLUSTER_INPUTS] compared pairs only for perf — the
 * corpus is ~500 stubs, so the O(n²) token comparison is trivial.
 * This threshold is deliberately conservative: it merges wire-copy
 * rewrites of one headline, not merely related coverage. Each cluster
 * keeps one representative: earliest `publishedAtMillis` (wire copy
 * originates once), tie-broken by fuller metadata (image, then more
 * keywords, then URL); the representative reports
 * `cluster size - 1` as [RankedArticle.alsoFromSources].
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
 * Latest is a plain newest-first sort with the same clustering
 * collapse (a duplicated wire story is one row in either order).
 */
object FeedRanking {

    const val HALF_LIFE_HOURS = 18.0
    const val IMAGE_BONUS = 5.0
    const val KEYWORD_BONUS = 2.0
    const val FIRST_PAGE_SIZE = 20
    const val FIRST_PAGE_PER_SOURCE = 3
    const val NEAR_DUPLICATE_JACCARD = 0.85
    const val MIN_TOKENS_FOR_NEAR_DUPLICATE = 4

    /** Rank [articles] for the feed; [nowMillis] is injected for tests. */
    fun rank(
        articles: List<FeedArticle>,
        sort: FeedSort,
        nowMillis: Long,
    ): List<RankedArticle> {
        val clusters = cluster(articles)
        return when (sort) {
            FeedSort.LATEST -> clusters.sortedWith(
                compareByDescending<RankedArticle> { it.article.publishedAtMillis }
                    .thenBy { it.article.url },
            )
            FeedSort.TOP -> diversify(clusters, nowMillis)
        }
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

    /** Normalised comparison form of a headline (see KDoc). */
    fun normaliseTitle(title: String): String {
        var text = title.trim().lowercase()
        // Strip a leading "source:" / "source -" wire prefix, if it is short.
        val separators = listOf(text.indexOf(": "), text.indexOf(" - ")).filter { it >= 0 }
        val prefixEnd = separators.minOrNull() ?: -1
        if (prefixEnd in 1..24) {
            val skip = if (text.startsWith(": ", prefixEnd)) 2 else 3
            text = text.substring(prefixEnd + skip).trim()
        }
        return text.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
    }

    private fun cluster(articles: List<FeedArticle>): List<RankedArticle> {
        if (articles.isEmpty()) return emptyList()
        val normalised = articles.map { normaliseTitle(it.title) }
        val tokens = normalised.map { if (it.isEmpty()) emptySet() else it.split(' ').toSet() }
        val parent = IntArray(articles.size) { it }
        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            return root
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }
        for (i in articles.indices) {
            if (normalised[i].isEmpty()) continue
            for (j in i + 1 until articles.size) {
                if (normalised[j].isEmpty()) continue
                if (normalised[i] == normalised[j]) {
                    union(i, j)
                } else if (tokens[i].size >= MIN_TOKENS_FOR_NEAR_DUPLICATE &&
                    tokens[j].size >= MIN_TOKENS_FOR_NEAR_DUPLICATE
                ) {
                    val unionSize = (tokens[i] union tokens[j]).size
                    if (unionSize > 0 &&
                        (tokens[i] intersect tokens[j]).size.toDouble() / unionSize >= NEAR_DUPLICATE_JACCARD
                    ) {
                        union(i, j)
                    }
                }
            }
        }
        return articles.indices.groupBy { find(it) }.values.map { group ->
            val members = group.map { articles[it] }
            val representative = members.sortedWith(
                compareBy<FeedArticle> { it.publishedAtMillis }
                    .thenByDescending { it.imageUrl != null }
                    .thenByDescending { it.keywords.size }
                    .thenBy { it.url },
            ).first()
            RankedArticle(representative, alsoFromSources = members.size - 1)
        }
    }

    private fun diversify(clusters: List<RankedArticle>, nowMillis: Long): List<RankedArticle> {
        val remaining = clusters.sortedWith(
            compareByDescending<RankedArticle> { score(it.article, nowMillis) }
                .thenByDescending { it.article.publishedAtMillis }
                .thenBy { it.article.url },
        ).toMutableList()
        val out = mutableListOf<RankedArticle>()
        while (remaining.isNotEmpty()) {
            val pick = if (out.size < FIRST_PAGE_SIZE) {
                val perSource = out.groupingBy { it.article.sourceId }.eachCount()
                remaining.firstOrNull { (perSource[it.article.sourceId] ?: 0) < FIRST_PAGE_PER_SOURCE }
                    ?: remaining.first()
            } else {
                val tail = out.takeLast(2)
                if (tail.size == 2 && tail.all { it.article.sourceId == tail[0].article.sourceId }) {
                    remaining.firstOrNull { it.article.sourceId != tail[0].article.sourceId }
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
