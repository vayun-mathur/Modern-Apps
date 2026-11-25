package com.vayunmathur.news.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Top-stories ranking: decay, diversity cap, mechanical story order, Latest. */
class FeedRankingTest {

    private val now = 1_700_000_000_000L
    private fun hoursAgo(hours: Double) = now - (hours * 3_600_000).toLong()

    private fun article(
        source: String,
        url: String,
        title: String = "Story $url",
        published: Long,
        image: String? = null,
        keywords: List<String> = emptyList(),
        serverStoryId: String? = null,
    ) = FeedArticle(url, source, title, published, image, keywords, serverStoryId = serverStoryId)

    @Test fun decayOrdersFreshBeforeDayOldBeforeThreeDaysOld() {
        val ranked = FeedRanking.rank(
            listOf(
                article("old-wire", "https://e/old", published = hoursAgo(72.0)),
                article("fresh-wire", "https://e/fresh", published = hoursAgo(1.0)),
                article("day-wire", "https://e/day", published = hoursAgo(24.0)),
            ),
            FeedSort.TOP, now,
        )
        assertEquals(
            listOf("https://e/fresh", "https://e/day", "https://e/old"),
            ranked.map { it.article.url },
        )
        // Day-old sinks but does not vanish: its score is still positive.
        assertTrue(FeedRanking.score(article("w", "https://e/x", published = hoursAgo(24.0)), now) > 20.0)
        assertTrue(FeedRanking.score(article("w", "https://e/x", published = 0L), now) == 0.0)
    }

    @Test fun firstPageCapsAnyOneSourceAtThree() {
        val flood = (1..30).map {
            article("bbc", "https://e/bbc-$it", published = hoursAgo(it * 0.1))
        }
        val others = (1..17).map { source ->
            article("wire-$source", "https://e/w$source", published = hoursAgo(6.0 + source))
        }
        val ranked = FeedRanking.rank(flood + others, FeedSort.TOP, now)
        val firstPage = ranked.take(FeedRanking.FIRST_PAGE_SIZE)
        assertEquals(20, firstPage.size)
        assertTrue(
            firstPage.groupingBy { it.article.sourceId }.eachCount().values.all {
                it <= FeedRanking.FIRST_PAGE_PER_SOURCE
            },
            "first page must cap a high-volume wire",
        )
        assertTrue(firstPage.map { it.article.sourceId }.distinct().size >= 7)
        // A one-source corpus still fills the page (cap relaxes).
        val solo = FeedRanking.rank(flood, FeedSort.TOP, now)
        assertEquals(20, solo.take(20).size)
        assertEquals(30, solo.size)
    }

    @Test fun identicalHeadlinesWithoutStoryIdDoNotCollapse() {
        val ranked = FeedRanking.rank(
            listOf(
                article("bbc", "https://e/b1", title = "Storm hits coast as winds rise", published = hoursAgo(2.0)),
                article("g1", "https://e/g1", title = "BBC: Storm hits coast as winds rise!", published = hoursAgo(5.0)),
                article("dw", "https://e/d1", title = "Winds rise as storm hits coast", published = hoursAgo(1.0)),
                article("npr", "https://e/n1", title = "Markets rally on jobs data", published = hoursAgo(1.0)),
            ),
            FeedSort.TOP, now,
        )
        assertEquals(4, ranked.size, "no storyId = no grouping, however similar the headlines")
        assertTrue(ranked.all { it.alsoFromSources == 0 })
    }

    @Test fun sharedStoryIdCollapsesToEarliestRepresentative() {
        val ranked = FeedRanking.rank(
            listOf(
                article("bbc", "https://e/b1", title = "Storm hits coast as winds rise", published = hoursAgo(2.0), serverStoryId = "s1"),
                article("g1", "https://e/g1", title = "Different wording entirely", published = hoursAgo(5.0), serverStoryId = "s1"),
                article("dw", "https://e/d1", title = "Winds rise as storm hits coast", published = hoursAgo(1.0), serverStoryId = "s1"),
                article("npr", "https://e/n1", title = "Markets rally on jobs data", published = hoursAgo(1.0)),
            ),
            FeedSort.TOP, now,
        )
        assertEquals(2, ranked.size, "only the server-grouped articles collapse to one row")
        val storm = ranked.first { it.alsoFromSources == 2 }
        assertEquals("https://e/g1", storm.article.url, "representative is the earliest member")
        // Latest keeps the same mechanical membership.
        val latest = FeedRanking.rank(
            listOf(
                article("bbc", "https://e/b1", title = "Same headline", published = hoursAgo(2.0), serverStoryId = "s1"),
                article("g1", "https://e/g1", title = "Same headline", published = hoursAgo(5.0), serverStoryId = "s1"),
            ),
            FeedSort.LATEST, now,
        )
        assertEquals(1, latest.size)
        assertEquals(1, latest[0].alsoFromSources)
    }

    @Test fun latestIsNewestFirstAndIgnoresMetadataBonus() {
        val ranked = FeedRanking.rank(
            listOf(
                article("a", "https://e/newer-plain", published = hoursAgo(1.0)),
                article(
                    "b", "https://e/older-rich", published = hoursAgo(2.0),
                    image = "https://e/img", keywords = listOf("k"),
                ),
            ),
            FeedSort.LATEST, now,
        )
        assertEquals(listOf("https://e/newer-plain", "https://e/older-rich"), ranked.map { it.article.url })
    }
}
