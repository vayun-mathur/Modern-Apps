package com.vayunmathur.news.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Region mapping, location matching, and Local ordering. */
class LocalNewsTest {

    @Test fun everyLocalSourceCarriesARegionAndNonLocalCarryNone() {
        val locals = SourceCatalog.sources.filter { NewsCategory.LOCAL in it.categories }
        // 36 curated + 328 generated (curate.py + curate_r2.py provenance).
        assertEquals(364, locals.size)
        for (source in locals) {
            assertTrue(!source.region.isNullOrBlank(), "${source.id} has no region")
        }
        for (source in SourceCatalog.sources - locals.toSet()) {
            assertEquals(null, source.region, "${source.id} should have no region")
        }
        assertEquals(locals.mapNotNull { it.region }.distinct().sorted(), SourceCatalog.localRegions)
    }

    @Test fun sampledRegionMapping() {
        fun region(id: String) = SourceCatalog.byId(id)!!.region
        // Curated city-level regions.
        assertEquals("Los Angeles, California, United States", region("latimes"))
        assertEquals("Los Angeles, California, United States", region("laist"))
        assertEquals("Chicago, Illinois, United States", region("block-club"))
        assertEquals("Texas, United States", region("texas-tribune"))
        assertEquals("Manchester, England, United Kingdom", region("manchester-evening"))
        assertEquals("London, England, United Kingdom", region("standard"))
        assertEquals("Vancouver, British Columbia, Canada", region("vancouver-sun"))
        assertEquals("Sydney, New South Wales, Australia", region("smh"))
        assertEquals("Hamburg, Germany", region("mopo"))
        assertEquals("Toulouse, France", region("ladepeche"))
        // Generated state-level regions from curate.py US-state provenance.
        assertEquals("Illinois, United States", region("advantagenews"))
        assertEquals("North Carolina, United States", region("adweek"))
        assertEquals("Arizona, United States", region("azcapitoltimes"))
        assertEquals("Alaska, United States", region("alaska-native-news"))
    }

    @Test fun matchingIsComponentWiseAndHierarchyAware() {
        assertTrue(LocalNews.matches("Los Angeles, California, United States", "Los Angeles, California, United States"))
        // State pick matches its cities, and a city pick matches statewide outlets.
        assertTrue(LocalNews.matches("Los Angeles, California, United States", "California, United States"))
        assertTrue(LocalNews.matches("California, United States", "Los Angeles, California, United States"))
        // Free-typed single place names.
        assertTrue(LocalNews.matches("San Francisco, California, United States", "California"))
        assertTrue(LocalNews.matches("Manchester, England, United Kingdom", "manchester"))
        assertTrue(LocalNews.matches("Toulouse, France", "Toulouse"))
        // Non-matches: other states/cities, country alone, unset/unknown.
        assertFalse(LocalNews.matches("Chicago, Illinois, United States", "California, United States"))
        assertFalse(LocalNews.matches("Chicago, Illinois, United States", "Los Angeles, California, United States"))
        assertFalse(LocalNews.matches("Chicago, Illinois, United States", "United States"))
        assertFalse(LocalNews.matches(null, "California"))
        assertFalse(LocalNews.matches("California, United States", null))
        assertFalse(LocalNews.matches("California, United States", "  "))
    }

    @Test fun catalogMatchingUsesSourceRegions() {
        assertTrue(SourceCatalog.matchesLocalLocation("latimes", "California"))
        assertTrue(SourceCatalog.matchesLocalLocation("calmatters", "Los Angeles, California, United States"))
        assertFalse(SourceCatalog.matchesLocalLocation("block-club", "California"))
        // Non-local sources never match, even for their home state.
        assertFalse(SourceCatalog.matchesLocalLocation("bbc", "London, England, United Kingdom"))
    }

    private fun article(sourceId: String) =
        FeedArticle(url = "https://e/$sourceId", sourceId = sourceId, title = "Story $sourceId", publishedAtMillis = 1L)

    @Test fun localOrderingPutsChosenLocationFirstStablyThenOthers() {
        val input = listOf(
            article("gothamist"), article("latimes"), article("block-club"), article("laist"),
        )
        // California first, preserving input order within each group.
        assertEquals(
            listOf("latimes", "laist", "gothamist", "block-club"),
            input.orderedForLocal("California, United States").map { it.sourceId },
        )
        assertEquals(
            listOf("block-club", "gothamist", "latimes", "laist"),
            input.orderedForLocal("Chicago, Illinois, United States").map { it.sourceId },
        )
    }

    @Test fun unsetLocationLeavesOrderUnchanged() {
        val input = listOf(article("gothamist"), article("latimes"), article("block-club"))
        assertEquals(input, input.orderedForLocal(null))
        assertEquals(input, input.orderedForLocal(""))
        assertEquals(input, input.orderedForLocal("   "))
    }
}
