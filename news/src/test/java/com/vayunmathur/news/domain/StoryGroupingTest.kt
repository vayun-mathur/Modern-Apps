package com.vayunmathur.news.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Story clustering precision/recall, model fields, server-wins, perf. */
class StoryGroupingTest {

    private val now = 1_700_000_000_000L
    private fun h(hours: Double) = now - (hours * 3_600_000).toLong()
    private fun a(
        source: String, url: String, title: String, published: Long,
        image: String? = null, keywords: List<String> = emptyList(),
        serverStoryId: String? = null, oneLiner: String? = null, lang: String? = null,
    ) = FeedArticle(url, source, title, published, image, keywords, language = lang, serverStoryId = serverStoryId, storyOneLiner = oneLiner)

    @Test fun sameEventMultiOutletMerges() {
        val stories = StoryGrouping.group(
            listOf(
                a("bbc", "https://e/b1", "Storm hits coast as winds rise", h(2.0)),
                a("guardian", "https://e/g1", "Storm hits coast as winds rise!", h(5.0)),
                a("dw", "https://e/d1", "Winds rise as storm hits coast", h(1.0)),
                a("npr", "https://e/n1", "Markets rally on jobs data", h(1.0)),
            ),
        )
        assertEquals(2, stories.size)
        val storm = stories.first { it.articles.size == 3 }
        assertEquals(3, storm.sourceCount)
        assertEquals("https://e/g1", storm.representative.url, "earliest telling is representative")
        assertEquals(storm.representative.url.let { FeedMerger.normaliseUrl(it) }, storm.id)
    }

    @Test fun differentEventsWithSimilarHeadlinesDontMerge() {
        val stories = StoryGrouping.group(
            listOf(
                a("bbc", "https://e/1", "Markets rally on jobs data", h(2.0)),
                a("dw", "https://e/2", "Markets rally on inflation data", h(1.0)),
                a("bbc", "https://e/3", "Council approves new housing plan", h(2.0)),
                a("dw", "https://e/4", "Council approves new transport plan", h(1.0)),
            ),
        )
        assertEquals(4, stories.size, "generic-headline lookalikes must stay separate")
    }

    @Test fun timeWindowSplitsRecurringHeadline() {
        val split = StoryGrouping.group(
            listOf(
                a("bbc", "https://e/1", "Storm hits coast as winds rise", h(80.0)),
                a("dw", "https://e/2", "Storm hits coast as winds rise", h(1.0)),
            ),
        )
        assertEquals(2, split.size, "identical headline >72h apart is a new story")
        // But a chain through an intermediate (each step <=72h) follows updates.
        val chained = StoryGrouping.group(
            listOf(
                a("bbc", "https://e/1", "Storm hits coast as winds rise", h(80.0)),
                a("dw", "https://e/2", "Storm hits coast as winds rise", h(40.0)),
                a("npr", "https://e/3", "Storm hits coast as winds rise", h(1.0)),
            ),
        )
        assertEquals(1, chained.size)
    }

    @Test fun updateChainMergesThroughEvolvingHeadlines() {
        val stories = StoryGrouping.group(
            listOf(
                a("bbc", "https://e/1", "Hurricane lands in Florida", h(10.0)),
                a("dw", "https://e/2", "Hurricane death toll rises in Florida", h(6.0)),
                a("npr", "https://e/3", "Death toll rises as Florida begins cleanup after hurricane", h(2.0)),
            ),
        )
        assertEquals(1, stories.size, "updates around the same named event must follow into the story")
        assertEquals(listOf("https://e/1", "https://e/2", "https://e/3"), stories[0].articles.map { it.url }, "timeline earliest to latest")
    }

    @Test fun storyModelFields() {
        val story = StoryGrouping.group(
            listOf(
                a("bbc", "https://e/1", "Storm hits coast", h(5.0), image = "https://e/old.jpg", lang = "en"),
                a("techcrunch", "https://e/2", "Storm hits coast", h(1.0), image = "https://e/new.jpg", lang = "en"),
            ),
        ).single()
        assertEquals(h(5.0), story.firstSeenMillis)
        assertEquals(h(1.0), story.latestMillis)
        assertEquals("https://e/new.jpg", story.heroImageUrl, "hero is latest image")
        assertEquals(listOf("bbc", "techcrunch"), story.sources)
        assertTrue(NewsCategory.WORLD in story.categories && NewsCategory.TECHNOLOGY in story.categories)
        assertEquals(listOf("en"), story.languages)
        assertTrue(story.matchesCategory(NewsCategory.TECHNOLOGY), "category matches any member")
    }

    @Test fun serverGroupingWinsAndNeverMergesDifferentServerStories() {
        val stories = StoryGrouping.group(
            listOf(
                // Server groups these despite unrelated headlines/times.
                a("bbc", "https://e/1", "First angle on the event", h(50.0), serverStoryId = "s1", oneLiner = "One line."),
                a("dw", "https://e/2", "Completely different wording here", h(1.0), serverStoryId = "s1"),
                // Identical headline but a different server story: must not merge in.
                a("npr", "https://e/3", "First angle on the event", h(1.0), serverStoryId = "s2"),
                // Ungrouped identical headline may join s2's story via client rules? It has no server id,
                // exact + within window vs e/3, so it joins s2 (allowed), never s1.
                a("guardian", "https://e/4", "First angle on the event", h(1.0)),
            ),
        )
        assertEquals(2, stories.size)
        assertTrue(stories.none { story ->
            story.articles.any { it.url == "https://e/1" } && story.articles.any { it.url == "https://e/3" }
        }, "different server stories must never be bridged by an ungrouped article")
        val s1 = stories.first { it.articles.any { a -> a.url == "https://e/1" } }
        assertEquals("srv:s1", s1.id)
        assertEquals("One line.", s1.oneLiner)
        assertTrue(s1.articles.map { it.url }.containsAll(listOf("https://e/1", "https://e/2")))
        assertNotEquals(s1.id, stories.first { it.serverStoryId == "s2" && it.id != s1.id }.id)
    }

    @Test fun rankingFoldsCrossSourceBreadthAndLatestUsesLatestUpdate() {
        val multi = (1..4).map { i ->
            a("s$i", "https://e/m$i", "Storm hits coast as winds rise", h(24.0 - i))
        }
        val single = listOf(a("solo", "https://e/solo", "Obscure local council meets", h(20.0)))
        val top = FeedRanking.rankStories(multi + single, FeedSort.TOP, now)
        assertEquals("https://e/m1", top.first().representative.url, "4-source day-old beats 1-source fresher-ish")
        assertTrue(
            FeedRanking.storyScore(top.first(), now) > FeedRanking.storyScore(
                StoryGrouping.group(single).single(), now,
            ),
        )
        // Latest: story whose latest update is newest wins even if it started earlier.
        val latest = FeedRanking.rankStories(multi + single, FeedSort.LATEST, now)
        assertEquals(h(20.0).coerceAtLeast(0) * 0 + multi.maxOf { it.publishedAtMillis }, latest.first().latestMillis)
    }

    @Test fun searchHitsSurfaceTheirStory() {
        val stories = StoryGrouping.group(
            listOf(
                a("bbc", "https://e/1", "Storm hits coast as winds rise", h(5.0)),
                a("dw", "https://e/2", "Winds rise as storm hits coast", h(1.0)),
                a("npr", "https://e/3", "Markets rally on jobs data", h(1.0)),
            ),
        )
        val surfaced = stories.orderedBySearchHits(listOf("https://e/3", "https://e/2"))
        assertEquals(2, surfaced.size)
        assertEquals(1, surfaced[0].articles.size)
        assertEquals(2, surfaced[1].articles.size, "a body hit on one member surfaces the whole story")
    }

    @Test fun clustering2500ArticlesIsFastEnough() {
        // 2,500 stubs: 500 repeating events x 5 outlets + distinct filler titles.
        val articles = (0 until 500).flatMap { event ->
            (0 until 5).map { outlet ->
                a(
                    "s$outlet", "https://e/$event-$outlet",
                    "Event $event storm hits coast as winds rise",
                    h((event % 24).toDouble()),
                )
            }
        }
        val start = System.nanoTime()
        val stories = StoryGrouping.group(articles)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(500, stories.size)
        println("StoryGrouping 2500 articles: ${elapsedMs}ms -> ${stories.size} stories")
        assertTrue(elapsedMs < 5_000, "clustering 2500 took ${elapsedMs}ms")
    }
}
