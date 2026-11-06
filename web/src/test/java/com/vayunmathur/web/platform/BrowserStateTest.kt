package com.vayunmathur.web.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowserStateTest {

    // ---------------------------------------------------------- BrowserTab

    @Test
    fun `a blank url is a new tab`() {
        assertTrue(BrowserTab(id = "1", url = "").isNewTab)
        assertTrue(BrowserTab(id = "1", url = "about:blank").isNewTab)
        assertFalse(BrowserTab(id = "1", url = "https://example.com").isNewTab)
    }

    // ---------------------------------------------------------- SearchEngine

    @Test
    fun `engines build query urls`() {
        assertEquals(
            "https://duckduckgo.com/?q=hello%20world",
            SearchEngine.DUCKDUCKGO.buildQueryUrl("hello world"),
        )
        assertEquals(
            "https://www.google.com/search?q=hello%20world",
            SearchEngine.GOOGLE.buildQueryUrl("hello world"),
        )
    }

    @Test
    fun `safe search appends the strict filter`() {
        val url = SearchEngine.GOOGLE.safeSearchUrl("cats")
        assertTrue(url.contains("safe=active"), url)
        assertTrue(url.contains("cats"), url)
    }

    @Test
    fun `an engine without a filter param falls back`() {
        val url = SearchEngine.STARTPAGE.safeSearchUrl("cats")
        assertTrue(url.contains("kp=1"), url)
    }

    @Test
    fun `fromName resolves and defaults`() {
        assertEquals(SearchEngine.BING, SearchEngine.fromName("BING"))
        assertEquals(SearchEngine.DEFAULT, SearchEngine.fromName("NOPE"))
    }

    @Test
    fun `self-hosted is only effective in dev builds`() {
        val eff = SearchEngine.effective(SearchEngine.SELF_HOSTED)
        assertTrue(eff == SearchEngine.SELF_HOSTED || eff == SearchEngine.DEFAULT)
        assertEquals(SearchEngine.GOOGLE, SearchEngine.effective(SearchEngine.GOOGLE))
    }

    // ---------------------------------------------------------- BrowserUtils

    @Test
    fun `empty input goes home`() {
        assertEquals(
            SearchEngine.DUCKDUCKGO.homepage,
            BrowserUtils.toNavigationUrl("", SearchEngine.DUCKDUCKGO),
        )
    }

    @Test
    fun `words become searches`() {
        assertTrue(
            BrowserUtils.toNavigationUrl("cute cats").contains("cute"),
        )
    }

    @Test
    fun `public hosts gain https`() {
        assertEquals("https://example.com", BrowserUtils.toNavigationUrl("example.com"))
    }

    @Test
    fun `safe search applies to the search branch only`() {
        val searched = BrowserUtils.toNavigationUrl("cats", SearchEngine.GOOGLE, safeSearch = true)
        assertTrue(searched.contains("safe=active"), searched)
        assertEquals(
            "https://example.com",
            BrowserUtils.toNavigationUrl("example.com", SearchEngine.GOOGLE, safeSearch = true),
        )
    }

    @Test
    fun `host and origin parse`() {
        assertEquals("example.com", BrowserUtils.hostFromUrl("https://example.com/a?b=1"))
        assertEquals("https://example.com", BrowserUtils.originFromUrl("https://example.com/a"))
        assertEquals(
            "http://router:8080",
            BrowserUtils.originFromUrl("http://router:8080/x"),
        )
    }

    @Test
    fun `pretty url shows host and short path`() {
        assertEquals("", BrowserUtils.prettyUrl(""))
        assertEquals("example.com", BrowserUtils.prettyUrl("https://example.com/"))
        assertEquals(
            "example.com/a",
            BrowserUtils.prettyUrl("https://example.com/a"),
        )
        // A very long path collapses to the host.
        assertEquals(
            "example.com",
            BrowserUtils.prettyUrl("https://example.com/" + "a".repeat(100)),
        )
    }

    @Test
    fun `full url display is verbatim`() {
        assertEquals("https://example.com/x", BrowserUtils.displayFullUrl("https://example.com/x"))
    }
}
