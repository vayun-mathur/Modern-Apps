package com.vayunmathur.news

import com.vayunmathur.news.platform.NewsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Sources page was removed entirely: there is no Sources route,
 * no Sources screen class, and no sources-only ViewModel state. The
 * catalog data (SourceCatalog) stays — only the page is gone. The Local
 * news location setting lives on the Settings surface, not Sources.
 */
class NavigationTest {

    @Test fun routeDestinations_doNotIncludeSources() {
        val destinations = Route::class.java.declaredClasses.map { it.simpleName }
        assertFalse(
            destinations.contains("Sources"),
            "Route must have no Sources destination, was: $destinations",
        )
        // The remaining destinations are untouched.
        assertTrue(destinations.contains("Feed"), "was: $destinations")
        assertTrue(destinations.contains("Reader"), "was: $destinations")
        assertTrue(destinations.contains("Saved"), "was: $destinations")
        // Settings / LocalLocation (parallel settings surface) must stay.
        assertTrue(destinations.contains("Settings"), "was: $destinations")
        assertTrue(destinations.contains("LocalLocation"), "was: $destinations")
    }

    @Test fun sourcesScreenClass_isGone() {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("com.vayunmathur.news.ui.SourcesScreenKt")
        }
        assertFailsWith<ClassNotFoundException> {
            Class.forName("com.vayunmathur.news.Route\$Sources")
        }
    }

    @Test fun viewModel_hasNoSourcesOnlyState() {
        val accessors = NewsViewModel::class.java.methods.map { it.name }
        assertFalse(
            accessors.contains("getSourceArticleCounts"),
            "sourceArticleCounts only fed the removed Sources page",
        )
        // Feed/settings state that other screens use must stay.
        assertTrue(accessors.contains("getLocalLocation"))
        assertEquals(
            false,
            accessors.contains("getSources"),
        )
    }
}
