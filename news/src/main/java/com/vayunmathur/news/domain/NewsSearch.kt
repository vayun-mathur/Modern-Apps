package com.vayunmathur.news.domain

/**
 * On-device search ranking over the local corpus. Pure and total.
 *
 * Corpus + ceiling: this ranks candidates the repository already pulled
 * from Room with `LIKE` queries (titles / keywords / source ids on the
 * stubs, `blocksJson` on downloaded bodies). The cache is pruned to
 * ~500 stubs and bodies exist only for articles the user has opened,
 * so a `LIKE` scan is a few hundred rows / a few MB of JSON at most —
 * no FTS index is warranted at this scale. If the cache cap ever grows
 * by an order of magnitude, move bodies to an FTS table instead.
 *
 * Ranking tiers (newest first inside a tier):
 * 0. title matches, 1. source-name / keyword matches, 2. body-only matches.
 */
object NewsSearch {

    /** One searchable stub: a [FeedArticle] plus whether its cached body matched. */
    data class Candidate(
        val article: FeedArticle,
        val sourceName: String,
        val bodyHit: Boolean,
    )

    fun rank(query: String, candidates: List<Candidate>, limit: Int = 50): List<FeedArticle> {
        val phrase = query.trim().lowercase()
        if (phrase.isEmpty()) return emptyList()
        val terms = phrase.split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun String.matches(): Boolean =
            lowercase().let { text -> text.contains(phrase) || terms.any { text.contains(it) } }
        return candidates.mapNotNull { candidate ->
            val article = candidate.article
            val tier = when {
                article.title.matches() -> 0
                candidate.sourceName.matches() || article.keywords.any { it.matches() } -> 1
                candidate.bodyHit -> 2
                else -> return@mapNotNull null
            }
            tier to article
        }.sortedWith(
            compareBy<Pair<Int, FeedArticle>> { it.first }
                .thenByDescending { it.second.publishedAtMillis },
        ).take(limit).map { it.second }
    }
}
