package com.vayunmathur.news.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Category taxonomy: every catalog source is tagged, articles inherit
 * their source's category, and "All" (null) passes everything through.
 * Server and fallback articles are both [FeedArticle]s keyed by catalog
 * source id (the server parser resolves `source` to that id), so the
 * same [inCategory] mapping covers both paths.
 */
class SourceCatalogCategoryTest {

    @Test fun everyCatalogSourceHasSensibleCategories() {
        // 88 hand-curated + 1,250 generated (analysis/news-scale/curate.py).
        assertEquals(1338, SourceCatalog.sources.size)
        assertEquals(SourceCatalog.sources.size, SourceCatalog.sources.map { it.id }.distinct().size)
        for (source in SourceCatalog.sources) {
            assertTrue(source.categories.isNotEmpty(), "${source.id} has no category")
            assertEquals(source.categories.distinct(), source.categories, "${source.id} duplicate tags")
            assertEquals(source.categories.first(), source.primaryCategory)
        }
        // Each taxonomy category is the primary category of at least one source.
        for (category in NewsCategory.entries) {
            assertTrue(
                SourceCatalog.sources.any { it.primaryCategory == category },
                "no source primarily in $category",
            )
        }
    }

    @Test fun knownAssignmentsFollowTheScopeAndTopicRule() {
        fun primary(id: String) = SourceCatalog.byId(id)!!.primaryCategory
        // Non-US / US national generalists by scope.
        assertEquals(NewsCategory.WORLD, primary("bbc"))
        assertEquals(NewsCategory.WORLD, primary("elpais"))
        assertEquals(NewsCategory.WORLD, primary("lefigaro"))
        assertEquals(NewsCategory.US, primary("npr"))
        assertEquals(NewsCategory.US, primary("propublica"))
        // Single-topic verticals by topic, whatever their scope/language.
        assertEquals(NewsCategory.TECHNOLOGY, primary("techcrunch"))
        assertEquals(NewsCategory.TECHNOLOGY, primary("heise"))
        assertEquals(NewsCategory.SCIENCE, primary("stat"))
        // City/regional outlets are Local in any country.
        assertEquals(NewsCategory.LOCAL, primary("gothamist"))
        assertEquals(NewsCategory.LOCAL, primary("texas-tribune"))
        assertEquals(NewsCategory.LOCAL, primary("manchester-evening"))
        assertEquals(NewsCategory.LOCAL, primary("the-age"))
        // Secondary tags: definitional second beats only.
        assertTrue(NewsCategory.SCIENCE in SourceCatalog.categoriesOf("ars"))
        assertTrue(NewsCategory.US in SourceCatalog.categoriesOf("semafor"))
    }

    private fun article(sourceId: String, url: String = "https://e/$sourceId") =
        FeedArticle(url = url, sourceId = sourceId, title = "Story $sourceId", publishedAtMillis = 1L)

    @Test fun categoryFilterAppliesSourceMappingToArticles() {
        val articles = listOf(
            article("bbc"), article("techcrunch"), article("ars"),
            article("stat"), article("gothamist"), article("npr"),
        )
        assertEquals(
            listOf("bbc"),
            articles.inCategory(NewsCategory.WORLD).map { it.sourceId },
        )
        assertEquals(
            listOf("techcrunch", "ars"),
            articles.inCategory(NewsCategory.TECHNOLOGY).map { it.sourceId },
        )
        // Secondary tag counts: Ars is Technology-primary but also Science.
        assertEquals(
            listOf("ars", "stat"),
            articles.inCategory(NewsCategory.SCIENCE).map { it.sourceId },
        )
        assertEquals(
            listOf("gothamist"),
            articles.inCategory(NewsCategory.LOCAL).map { it.sourceId },
        )
        assertEquals(
            listOf("npr"),
            articles.inCategory(NewsCategory.US).map { it.sourceId },
        )
    }

    @Test fun serverResolvedAndFallbackArticlesFilterTheSame() {
        // Server path: "BBC News" (display name) resolves to catalog id bbc.
        val server = ServerFeedParser.parse(
            """{"articles":[
                {"title":"Srv World","url":"https://srv/w","source":"BBC News","published_at":1},
                {"title":"Srv Tech","url":"https://srv/t","source":"techcrunch","published_at":2},
                {"title":"Srv Unknown","url":"https://srv/u","source":"not-a-source","published_at":3}
            ]}""",
        )
        // Fallback path: crawl articles are built with the catalog id directly.
        val fallback = listOf(article("bbc", "https://e/fallback-bbc"), article("stat", "https://e/fallback-stat"))
        val combined = server + fallback
        assertEquals(
            listOf("https://srv/w", "https://e/fallback-bbc"),
            combined.inCategory(NewsCategory.WORLD).map { it.url },
        )
        assertEquals(
            listOf("https://srv/t"),
            combined.inCategory(NewsCategory.TECHNOLOGY).map { it.url },
        )
        // Unresolved server source: no category, matches a specific category never.
        assertFalse(SourceCatalog.matchesCategory("not-a-source", NewsCategory.WORLD))
    }

    @Test fun allPassthroughKeepsEverythingIncludingUnresolved() {
        val articles = listOf(article("bbc"), article("not-a-source"), article("stat"))
        assertEquals(articles, articles.inCategory(null))
        assertTrue(SourceCatalog.matchesCategory("not-a-source", null))
        assertTrue(SourceCatalog.matchesCategory("anything", null))
    }

    @Test fun categoriesAmongReflectsTheGivenSourcesInTaxonomyOrder() {
        assertEquals(
            NewsCategory.entries,
            SourceCatalog.categoriesAmong(SourceCatalog.sources),
        )
        assertEquals(
            listOf(NewsCategory.WORLD),
            SourceCatalog.categoriesAmong(listOf(SourceCatalog.byId("bbc")!!)),
        )
        // Removing STAT alone would not drop Science: Ars/Wired/MIT
        // still carry it as a secondary tag.
        val noStat = SourceCatalog.sources.filter { it.id != "stat" }
        assertTrue(NewsCategory.SCIENCE in SourceCatalog.categoriesAmong(noStat))
        val noScienceAtAll = SourceCatalog.sources.filter {
            NewsCategory.SCIENCE !in it.categories
        }
        assertFalse(NewsCategory.SCIENCE in SourceCatalog.categoriesAmong(noScienceAtAll))
    }

    @Test fun searchWithinCategoryKeepsOnlySelectedCategoryHits() {
        // Compose rule used by NewsViewModel.searchResults: NewsSearch
        // ranks over the whole corpus, then the category filter narrows
        // the ranked hits — searching searches within the category.
        val candidates = listOf(
            NewsSearch.Candidate(article("bbc", "https://e/world-hit"), "BBC News", bodyHit = false),
            NewsSearch.Candidate(article("techcrunch", "https://e/tech-hit"), "TechCrunch", bodyHit = false),
            NewsSearch.Candidate(article("gothamist", "https://e/local-hit"), "Gothamist", bodyHit = false),
        ).map { it.copy(article = it.article.copy(title = "Volcano story")) }
        val ranked = NewsSearch.rank("volcano", candidates)
        assertEquals(3, ranked.size)
        assertEquals(
            listOf("https://e/tech-hit"),
            ranked.inCategory(NewsCategory.TECHNOLOGY).map { it.url },
        )
        assertEquals(ranked, ranked.inCategory(null), "'All' search must not narrow hits")
    }
}
