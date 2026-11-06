package com.vayunmathur.web.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.vayunmathur.web.data.Bookmark
import com.vayunmathur.web.data.HistoryEntry
import com.vayunmathur.web.platform.SearchEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OmniboxSuggestionsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun show(
        draft: String = "",
        bookmarks: List<Bookmark> = emptyList(),
        history: List<HistoryEntry> = emptyList(),
        onNavigate: (String) -> Unit = {},
    ) {
        rule.setContent {
            OmniboxSuggestions(
                currentDraft = draft,
                filteredBookmarks = bookmarks,
                filteredHistory = history,
                searchEngine = SearchEngine.DUCKDUCKGO,
                onNavigate = onNavigate,
                paddingValues = PaddingValues(0.dp),
                atBottom = false,
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun `a draft row navigates on tap`() {
        var navigated: String? = null
        show(draft = "example.com", onNavigate = { navigated = it })
        rule.onNodeWithText("example.com").assertIsDisplayed()
        rule.onNodeWithText("example.com").performClick()
        rule.waitForIdle()
        assertEquals("example.com", navigated)
    }

    @Test
    fun `matching bookmarks render under a header`() {
        show(
            bookmarks = listOf(
                Bookmark(id = 1, url = "https://example.com/a", title = "Alpha"),
                Bookmark(id = 2, url = "https://other.org", title = ""),
            )
        )
        rule.onNodeWithText("Bookmarks").assertIsDisplayed()
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        // Untitled bookmarks fall back to the URL.
        rule.onNodeWithText("https://other.org").assertIsDisplayed()
    }

    @Test
    fun `matching history renders`() {
        show(
            history = listOf(
                HistoryEntry(url = "https://example.com/old", title = "Old page"),
            )
        )
        rule.onNodeWithText("Old page").assertIsDisplayed()
    }

    @Test
    fun `an empty draft shows no typed row`() {
        show(
            bookmarks = listOf(Bookmark(url = "https://example.com", title = "Example")),
        )
        rule.onNodeWithText("Example").assertIsDisplayed()
    }
}
