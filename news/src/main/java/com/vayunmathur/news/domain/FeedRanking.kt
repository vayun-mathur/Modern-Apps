package com.vayunmathur.news.domain

/**
 * Client-side feed ordering over the merged corpus. The server's
 * `news.json` stays newest-first; this is applied on device only, over
 * whatever category filter already narrowed the corpus. Search never
 * goes through here — it keeps its [NewsSearch] relevance ranking.
 *
 * Stories come from [StoryGrouping]: articles sharing the exact
 * server `storyId` form one story; every article without one is a
 * singleton story. Ranking only *orders* those stories — it never
 * merges, splits, or otherwise influences membership.
 *
 * The one feed ordering is source-count-first:
 * ```
 * 1. descending distinct source count ([Story.sourceCount])
 * 2. descending recency ([Story.latestMillis], newest first)
 * 3. descending [Story.firstSeenMillis], then [Story.id], so the
 *    order is deterministic for equal-count, equal-time stories
 * ```
 * Every story is ranked by this rule, singletons included: a story
 * carried by five sources always precedes a fresher story carried
 * by one, and recency only breaks ties between stories with the
 * same source count.
 */
object FeedRanking {

    /** Group [articles] into stories and order them for the feed. */
    fun rankStories(articles: List<FeedArticle>): List<Story> =
        rankGroupedStories(StoryGrouping.group(articles))

    /** Order already-grouped [stories] (grouping done once, ordering/filtering many times). */
    fun rankGroupedStories(stories: List<Story>): List<Story> =
        stories.sortedWith(
            compareByDescending<Story> { it.sourceCount }
                .thenByDescending { it.latestMillis }
                .thenByDescending { it.firstSeenMillis }
                .thenBy { it.id },
        )
}
