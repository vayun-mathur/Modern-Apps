package com.vayunmathur.news.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class FeedMergerTest {
    private fun article(url: String, at: Long) = FeedArticle(url, "s", url, at)

    @Test
    fun dedupesByNormalisedUrlAndSortsNewestFirst() {
        val merged = FeedMerger.merge(
            listOf(
                listOf(article("https://EXAMPLE.com/a/?utm_source=x#frag", 100L), article("https://example.com/b", 300L)),
                listOf(article("https://example.com/a", 200L), article("https://example.com/c", 50L)),
            ),
        )
        assertEquals(
            listOf("https://example.com/b", "https://example.com/a", "https://example.com/c"),
            merged.map { FeedMerger.normaliseUrl(it.url) },
        )
    }

    @Test
    fun respectsLimit() {
        val merged = FeedMerger.merge(listOf((1..10).map { article("https://e.com/$it", it.toLong()) }), limit = 3)
        assertEquals(3, merged.size)
        assertEquals(10L, merged.first().publishedAtMillis)
    }
}
