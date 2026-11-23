package com.vayunmathur.news.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class NewsSearchTest {
    private fun candidate(url: String, title: String, published: Long, bodyHit: Boolean = false, source: String = "BBC News") =
        NewsSearch.Candidate(FeedArticle(url, "bbc", title, published), source, bodyHit)

    @Test fun titleHitsRankBeforeBodyHits() {
        val ranked = NewsSearch.rank(
            "volcano",
            listOf(
                candidate("https://e/newer-body", "Unrelated title", 300, bodyHit = true),
                candidate("https://e/older-title", "Volcano erupts", 100),
                candidate("https://e/no-hit", "Other news", 400),
            ),
        )
        assertEquals(listOf("https://e/older-title", "https://e/newer-body"), ranked.map { it.url })
    }

    @Test fun withinTierNewestFirstAndBlankQueryEmpty() {
        val ranked = NewsSearch.rank(
            "election",
            listOf(
                candidate("https://e/old", "Election one", 100),
                candidate("https://e/new", "Election two", 200),
            ),
        )
        assertEquals(listOf("https://e/new", "https://e/old"), ranked.map { it.url })
        assertEquals(emptyList(), NewsSearch.rank("  ", listOf(candidate("https://e/x", "Election", 1))))
    }
}
