package com.vayunmathur.news.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Feed ordering: source-count-first, recency tiebreak, label logic. */
class FeedRankingTest {

    private val now = 1_700_000_000_000L
    private fun hoursAgo(hours: Double) = now - (hours * 3_600_000).toLong()

    private fun article(
        source: String,
        url: String,
        title: String = "Story $url",
        published: Long,
        serverStoryId: String? = null,
    ) = FeedArticle(url, source, title, published, serverStoryId = serverStoryId)

    private fun story(id: String, sources: List<String>, latestHoursAgo: Double): Story {
        val articles = sources.mapIndexed { i, source ->
            article(source, "https://e/$id-$i", published = hoursAgo(latestHoursAgo + i))
        }
        return Story(
            id = id,
            representative = articles.first(),
            articles = articles,
        )
    }

    @Test fun moreSourcesBeatsFresherFewerSources() {
        val ranked = FeedRanking.rankGroupedStories(
            listOf(
                story("fresh-single", listOf("a"), latestHoursAgo = 0.5),
                story("old-five", listOf("a", "b", "c", "d", "e"), latestHoursAgo = 72.0),
                story("mid-two", listOf("a", "b"), latestHoursAgo = 1.0),
            ),
        )
        assertEquals(listOf("old-five", "mid-two", "fresh-single"), ranked.map { it.id })
    }

    @Test fun recencyBreaksSourceCountTiesNewestFirst() {
        val ranked = FeedRanking.rankGroupedStories(
            listOf(
                story("older", listOf("a", "b"), latestHoursAgo = 10.0),
                story("newer", listOf("c", "d"), latestHoursAgo = 1.0),
                story("middle", listOf("e", "f"), latestHoursAgo = 5.0),
            ),
        )
        assertEquals(listOf("newer", "middle", "older"), ranked.map { it.id })
    }

    @Test fun singletonsAreRankedStoriesToo() {
        val ranked = FeedRanking.rankStories(
            listOf(
                article("solo", "https://e/solo", published = hoursAgo(0.1)),
                article("bbc", "https://e/b1", published = hoursAgo(50.0), serverStoryId = "s1"),
                article("dw", "https://e/d1", published = hoursAgo(49.0), serverStoryId = "s1"),
            ),
        )
        assertEquals(2, ranked.size)
        assertEquals("srv:s1", ranked[0].id)
        assertEquals(1, ranked[1].articles.size)
        assertEquals("1 source", ranked[1].sourceCountLabel)
    }

    @Test fun identicalHeadlinesWithoutStoryIdDoNotCollapse() {
        val ranked = FeedRanking.rankStories(
            listOf(
                article("bbc", "https://e/b1", title = "Storm hits coast as winds rise", published = hoursAgo(2.0)),
                article("g1", "https://e/g1", title = "BBC: Storm hits coast as winds rise!", published = hoursAgo(5.0)),
                article("dw", "https://e/d1", title = "Winds rise as storm hits coast", published = hoursAgo(1.0)),
            ),
        )
        assertEquals(3, ranked.size, "no storyId = no grouping, however similar the headlines")
        assertTrue(ranked.all { it.sourceCount == 1 && it.sourceCountLabel == "1 source" })
    }

    @Test fun sharedStoryIdCollapsesAndCountsDistinctSources() {
        val ranked = FeedRanking.rankStories(
            listOf(
                article("bbc", "https://e/b1", published = hoursAgo(2.0), serverStoryId = "s1"),
                article("bbc", "https://e/b2", published = hoursAgo(3.0), serverStoryId = "s1"),
                article("dw", "https://e/d1", published = hoursAgo(1.0), serverStoryId = "s1"),
                article("npr", "https://e/n1", published = hoursAgo(0.5)),
            ),
        )
        assertEquals(2, ranked.size, "only the server-grouped articles collapse to one story")
        val grouped = ranked.first { it.id == "srv:s1" }
        // Two BBC articles + one DW article = 2 distinct sources, 3 articles.
        assertEquals(2, grouped.sourceCount)
        assertEquals(3, grouped.articleCount)
        assertEquals("2 sources", grouped.sourceCountLabel)
        assertEquals("srv:s1", ranked[0].id, "2-source story precedes fresher singleton")
    }

    @Test fun sourceCountLabelSingularAndPlural() {
        assertEquals("1 source", story("one", listOf("a"), 1.0).sourceCountLabel)
        assertEquals("2 sources", story("two", listOf("a", "b"), 1.0).sourceCountLabel)
        assertEquals("5 sources", story("five", listOf("a", "b", "c", "d", "e"), 1.0).sourceCountLabel)
    }

    @Test fun equalCountAndTimeOrderIsDeterministicById() {
        val a = story("srv:b", listOf("a", "b"), latestHoursAgo = 1.0)
        val b = story("srv:a", listOf("c", "d"), latestHoursAgo = 1.0)
        // Same source count; latest/firstSeen also tie only if timestamps match —
        // story() offsets members by index, so both share the same instants.
        assertEquals(listOf("srv:a", "srv:b"), FeedRanking.rankGroupedStories(listOf(a, b)).map { it.id })
        assertEquals(listOf("srv:a", "srv:b"), FeedRanking.rankGroupedStories(listOf(b, a)).map { it.id })
    }
}
