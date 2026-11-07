package com.vayunmathur.web.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.web.data.WebSearchNewsItem
import com.vayunmathur.web.data.WebSearchTextItem
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchListsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `text results open on tap`() {
        var opened: String? = null
        rule.setContent {
            SearchTextResults(
                items = listOf(
                    WebSearchTextItem(
                        title = "Example",
                        url = "https://example.com",
                        snippet = "A site",
                    ),
                    WebSearchTextItem(title = "", url = "https://bare.org", snippet = ""),
                ),
                loadingMore = false,
                onOpenResult = { opened = it },
                onLoadMore = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Example").assertIsDisplayed()
        rule.onNodeWithText("A site").assertIsDisplayed()
        // Untitled results fall back to the URL.
        rule.onNodeWithText("https://bare.org").assertIsDisplayed()
        rule.onNodeWithText("Example").performClick()
        rule.waitForIdle()
        assertEquals("https://example.com", opened)
    }

    @Test
    fun `an empty text list shows the empty state`() {
        rule.setContent {
            SearchTextResults(
                items = emptyList(),
                loadingMore = false,
                onOpenResult = {},
                onLoadMore = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("No results found").assertIsDisplayed()
    }

    @Test
    fun `news results show source and open on tap`() {
        var opened: String? = null
        rule.setContent {
            SearchNewsResults(
                items = listOf(
                    WebSearchNewsItem(
                        title = "Big news",
                        url = "https://news.example/a",
                        snippet = "Details",
                        date = 0L,
                        source = "Example News",
                    ),
                ),
                onOpenResult = { opened = it },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Big news").assertIsDisplayed()
        rule.onNodeWithText("Example News", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Big news").performClick()
        rule.waitForIdle()
        assertEquals("https://news.example/a", opened)
    }

    @Test
    fun `the link menu copies shares and opens tabs`() {
        var copied = false
        var shared = false
        var newTab = false
        var opened = false
        rule.setContent {
            LinkContextMenu(
                url = "https://example.com/page",
                onDismiss = {},
                onCopyLink = { copied = true },
                onShareLink = { shared = true },
                onOpenInNewTab = { newTab = true },
                onOpenLink = { opened = true },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("https://example.com/page").assertIsDisplayed()
        rule.onNodeWithText("Copy", substring = true).performClick()
        rule.waitForIdle()
        assertEquals(true, copied)
        rule.onNodeWithText("Share", substring = true).performClick()
        rule.waitForIdle()
        assertEquals(true, shared)
    }
}
