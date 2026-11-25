package com.vayunmathur.news.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mechanical story grouping: exact server `storyId` key only.
 * No client-side grouping exists — identical headlines, shared
 * entities/keywords and close timestamps must never merge articles
 * the server did not group.
 */
class StoryGroupingTest {

    private val now = 1_700_000_000_000L
    private fun h(hours: Double) = now - (hours * 3_600_000).toLong()
    private fun a(
        source: String, url: String, title: String, published: Long,
        image: String? = null, keywords: List<String> = emptyList(),
        serverStoryId: String? = null, oneLiner: String? = null, lang: String? = null,
        serverStoryTitle: String? = null,
    ) = FeedArticle(
        url, source, title, published, image, keywords, language = lang,
        serverStoryId = serverStoryId, storyOneLiner = oneLiner, serverStoryTitle = serverStoryTitle,
    )

    @Test fun sameStoryIdGroupsRegardlessOfHeadlineOrTime() {
        val stories = StoryGrouping.group(
            listOf(
                a("bbc", "https://e/b1", "Storm hits coast as winds rise", h(50.0), serverStoryId = "s1"),
                a("dw", "https://e/d1", "Completely different wording here", h(1.0), serverStoryId = "s1"),
                a("npr", "https://e/n1", "Markets rally on jobs data", h(1.0)),
            ),
        )
        assertEquals(2, stories.size)
        val grouped = stories.first { it.id == "srv:s1" }
        assertEquals(2, grouped.articles.size)
        assertEquals(2, grouped.sourceCount)
        assertEquals("s1", grouped.serverStoryId)
        // Timeline order is earliest -> latest within the server story.
        assertEquals(listOf("https://e/b1", "https://e/d1"), grouped.articles.map { it.url })
        assertEquals("https://e/b1", grouped.representative.url, "earliest member is representative")
    }

    @Test fun missingStoryIdIsAlwaysSingleton() {
        val stories = StoryGrouping.group(
            listOf(
                a("bbc", "https://e/1", "Storm hits coast as winds rise", h(2.0)),
                a("dw", "https://e/2", "Storm hits coast as winds rise", h(1.0)),
                a("npr", "https://e/3", "Storm hits coast as winds rise", h(1.0)),
            ),
        )
        // Identical headlines + close timestamps: still no local merge.
        assertEquals(3, stories.size)
        assertTrue(stories.all { it.articles.size == 1 && it.serverStoryId == null })
        assertTrue(stories.all { it.sourceCount == 1 })
        assertEquals(
            setOf("https://e/1", "https://e/2", "https://e/3"),
            stories.map { it.representative.url }.toSet(),
        )
        // Singleton id is the normalised sole-article URL.
        assertTrue(stories.all { it.id == FeedMerger.normaliseUrl(it.representative.url) })
    }

    @Test fun blankStoryIdIsSingleton() {
        val stories = StoryGrouping.group(
            listOf(
                a("bbc", "https://e/1", "Same headline", h(1.0), serverStoryId = ""),
                a("dw", "https://e/2", "Same headline", h(1.0), serverStoryId = "  "),
            ),
        )
        assertEquals(2, stories.size)
        assertTrue(stories.all { it.articles.size == 1 })
    }

    @Test fun differentStoryIdsNeverMergedEvenWithIdenticalHeadlines() {
        val stories = StoryGrouping.group(
            listOf(
                a("bbc", "https://e/1", "First angle on the event", h(1.0), serverStoryId = "s1", oneLiner = "One line."),
                a("dw", "https://e/2", "First angle on the event", h(1.0), serverStoryId = "s2"),
                // Ungrouped identical headline joins neither server story.
                a("guardian", "https://e/3", "First angle on the event", h(1.0)),
            ),
        )
        assertEquals(3, stories.size)
        val s1 = stories.first { it.id == "srv:s1" }
        assertEquals(listOf("https://e/1"), s1.articles.map { it.url })
        assertEquals("One line.", s1.oneLiner)
        val s2 = stories.first { it.id == "srv:s2" }
        assertEquals(listOf("https://e/2"), s2.articles.map { it.url })
        val singleton = stories.first { it.serverStoryId == null }
        assertEquals(listOf("https://e/3"), singleton.articles.map { it.url })
    }

    @Test fun serverTitleAndOneLinerUsedVerbatim() {
        val story = StoryGrouping.group(
            listOf(
                a(
                    "bbc", "https://e/1", "Outlet headline one", h(5.0),
                    serverStoryId = "s1", serverStoryTitle = "Canonical server title",
                ),
                a(
                    "dw", "https://e/2", "Outlet headline two", h(1.0),
                    serverStoryId = "s1", oneLiner = "Server summary.",
                ),
            ),
        ).single()
        assertEquals("Canonical server title", story.title)
        assertEquals("Server summary.", story.oneLiner)
        // Without a server title, the representative's headline is used.
        val noServerTitle = StoryGrouping.group(
            listOf(a("bbc", "https://e/1", "Only headline", h(1.0), serverStoryId = "s9")),
        ).single()
        assertEquals("Only headline", noServerTitle.title)
        assertNull(noServerTitle.oneLiner)
    }

    @Test fun storyModelFieldsFromActualMembers() {
        val story = StoryGrouping.group(
            listOf(
                a("bbc", "https://e/1", "Storm hits coast", h(5.0), image = "https://e/old.jpg", lang = "en", serverStoryId = "s1"),
                a("techcrunch", "https://e/2", "Storm hits coast", h(1.0), image = "https://e/new.jpg", lang = "en", serverStoryId = "s1"),
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

    @Test fun rankingOrdersButNeverChangesMembership() {
        // Four same-headline articles the server grouped (s1) + a fresher singleton.
        val multi = (1..4).map { i ->
            a("s$i", "https://e/m$i", "Storm hits coast as winds rise", h(24.0 - i), serverStoryId = "s1")
        }
        val single = listOf(a("solo", "https://e/solo", "Obscure local council meets", h(20.0)))
        val top = FeedRanking.rankStories(multi + single, FeedSort.TOP, now)
        assertEquals(2, top.size, "ranking orders the 2 mechanical stories; it does not create or dissolve any")
        assertEquals(4, top.first { it.id == "srv:s1" }.articles.size)
        assertTrue(
            FeedRanking.storyScore(top.first { it.id == "srv:s1" }, now) >
                FeedRanking.storyScore(StoryGrouping.group(single).single(), now),
            "cross-source breadth (actual server membership) folds into Top ordering",
        )
        val latest = FeedRanking.rankStories(multi + single, FeedSort.LATEST, now)
        assertEquals(2, latest.size)
        assertEquals(multi.maxOf { it.publishedAtMillis }, latest.first().latestMillis)
        // Without a shared storyId the same headlines stay 5 separate ranked rows.
        val ungrouped = (1..4).map { i ->
            a("s$i", "https://e/u$i", "Storm hits coast as winds rise", h(24.0 - i))
        }
        assertEquals(5, FeedRanking.rankStories(ungrouped + single, FeedSort.TOP, now).size)
    }

    @Test fun searchHitsSurfaceTheirStory() {
        val stories = StoryGrouping.group(
            listOf(
                a("bbc", "https://e/1", "Storm hits coast as winds rise", h(5.0), serverStoryId = "s1"),
                a("dw", "https://e/2", "Winds rise as storm hits coast", h(1.0), serverStoryId = "s1"),
                a("npr", "https://e/3", "Markets rally on jobs data", h(1.0)),
            ),
        )
        val surfaced = stories.orderedBySearchHits(listOf("https://e/3", "https://e/2"))
        assertEquals(2, surfaced.size)
        assertEquals(1, surfaced[0].articles.size)
        assertEquals(2, surfaced[1].articles.size, "a hit on one member surfaces its whole server story")
    }

    @Test fun noLocalMergeCodePathsRemain() {
        // Grep-level equivalent (reflection, no file IO): StoryGrouping
        // exposes only the mechanical `group` entry point — no merge
        // predicate, candidate generation, feature extraction, or
        // similarity thresholds remain.
        val memberNames = StoryGrouping::class.java.declaredMethods.map { it.name } +
            StoryGrouping::class.java.declaredFields.map { it.name } +
            StoryGrouping::class.java.declaredClasses.map { it.simpleName }
        assertTrue(memberNames.none { it.contains("merge", ignoreCase = true) }, "local merge member remains: $memberNames")
        assertTrue(memberNames.none { it.contains("jaccard", ignoreCase = true) }, "similarity member remains: $memberNames")
        assertTrue(memberNames.none { it.contains("candidate", ignoreCase = true) }, "candidate generation remains: $memberNames")
        assertTrue(memberNames.none { it.contains("feature", ignoreCase = true) }, "feature extraction remains: $memberNames")
        assertTrue(memberNames.none { it.contains("window", ignoreCase = true) }, "time-window member remains: $memberNames")
        assertEquals(setOf("group"), StoryGrouping::class.java.declaredMethods.map { it.name }.filter { !it.startsWith("$") }.toSet())
    }

    @Test fun groupingManyArticlesIsMechanicalAndFast() {
        // 2,500 stubs: 500 server stories x 5 outlets + distinct filler titles
        // with identical wording but no storyId (must stay singleton).
        val grouped = (0 until 250).flatMap { event ->
            (0 until 5).map { outlet ->
                a("s$outlet", "https://e/$event-$outlet", "Event $event storm hits coast", h((event % 24).toDouble()), serverStoryId = "srv-$event")
            }
        }
        val ungrouped = (0 until 1250).map { i ->
            a("s${i % 5}", "https://e/u$i", "Event ${i % 250} storm hits coast", h(1.0))
        }
        val start = System.nanoTime()
        val stories = StoryGrouping.group(grouped + ungrouped)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(250 + 1250, stories.size)
        println("StoryGrouping 2500 articles: ${elapsedMs}ms -> ${stories.size} stories")
        assertTrue(elapsedMs < 5_000, "grouping 2500 took ${elapsedMs}ms")
    }
}
