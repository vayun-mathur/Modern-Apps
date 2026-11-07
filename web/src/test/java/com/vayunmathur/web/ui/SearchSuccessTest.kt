package com.vayunmathur.web.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.vayunmathur.web.data.WebSearchImageItem
import com.vayunmathur.web.data.WebSearchKind
import com.vayunmathur.web.data.WebSearchNewsItem
import com.vayunmathur.web.data.WebSearchRepository
import com.vayunmathur.web.data.WebSearchResponse
import com.vayunmathur.web.data.WebSearchTextItem
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SearchSuccessTest {

    @get:Rule val rule = createComposeRule()

    @AfterTest fun clearOverride() {
        WebSearchRepository.searchOverride = null
    }

    private fun awaitNode(text: String) {
        runBlocking {
            repeat(300) {
                rule.waitForIdle()
                shadowOf(Looper.getMainLooper()).idle()
                val found = runCatching {
                    rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
                if (found) return@runBlocking
                delay(10)
            }
            assertTrue(
                runCatching { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false),
                "node '$text' never appeared",
            )
        }
    }

    private fun stubSearch(handler: suspend (String, WebSearchKind, Boolean, Int) -> WebSearchResponse) {
        WebSearchRepository.searchOverride = handler
    }

    @Test fun textResultsRenderAndOpen() {
        stubSearch { q, _, _, page ->
            WebSearchResponse(
                kind = "text",
                query = q,
                text = listOf(
                    WebSearchTextItem(title = "Result One", url = "https://one.example", snippet = "First snippet"),
                    WebSearchTextItem(title = "Result Two", url = "https://two.example", snippet = "Second snippet"),
                ),
            )
        }
        var opened: String? = null
        rule.setContent {
            SearchResultsPage(query = "kotlin", onOpenResult = { opened = it }, onOpenFallback = {})
        }
        awaitNode("Result One")
        rule.onNodeWithText("Result One").assertIsDisplayed()
        rule.onNodeWithText("Result One").performClick()
        rule.waitForIdle()
        assertEquals("https://one.example", opened)
    }

    @Test fun loadMoreAppendsNextPage() {
        stubSearch { q, _, _, page ->
            WebSearchResponse(
                kind = "text",
                query = q,
                text = listOf(
                    WebSearchTextItem(title = "Page${page}Result", url = "https://p$page.example", snippet = "s"),
                ),
            )
        }
        rule.setContent {
            SearchResultsPage(query = "paging", onOpenResult = {}, onOpenFallback = {})
        }
        awaitNode("Page1Result")
        rule.onNodeWithText("More results", substring = true).performClick()
        rule.waitForIdle()
        awaitNode("Page2Result")
        // Both pages are present after appending.
        assertTrue(rule.onAllNodes(hasText("Page1Result")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun newsTabRendersNews() {
        stubSearch { q, kind, _, _ ->
            when (kind) {
                WebSearchKind.NEWS -> WebSearchResponse(
                    kind = "news",
                    query = q,
                    news = listOf(WebSearchNewsItem(title = "Big Story", url = "https://news.example/1", snippet = "sn", date = 1700000000, source = "Wire")),
                )
                else -> WebSearchResponse(kind = "text", query = q, text = emptyList())
            }
        }
        var opened: String? = null
        rule.setContent {
            SearchResultsPage(query = "headlines", onOpenResult = { opened = it }, onOpenFallback = {})
        }
        awaitNode("Web", )
        rule.onNodeWithText("News").performClick()
        rule.waitForIdle()
        awaitNode("Big Story")
        rule.onNodeWithText("Big Story").performClick()
        rule.waitForIdle()
        assertEquals("https://news.example/1", opened)
    }

    @Test fun imagesTabRendersGrid() {
        stubSearch { q, kind, _, _ ->
            WebSearchResponse(
                kind = "images",
                query = q,
                images = listOf(
                    WebSearchImageItem(title = "Cat Pic", imageUrl = "https://img.example/cat.png", thumbnail = "", sourceUrl = "https://src.example/cat"),
                ),
            )
        }
        rule.setContent {
            SearchResultsPage(query = "cats", onOpenResult = {}, onOpenFallback = {})
        }
        awaitNode("Images")
        rule.onNodeWithText("Images").performClick()
        rule.waitForIdle()
        awaitNode("More results")
    }

    @Test fun degradedResponseShowsErrorWithFallback() {
        stubSearch { q, _, _, _ ->
            WebSearchResponse(kind = "text", query = q, text = emptyList(), error = "upstream blocked", fallbackUrl = "https://fallback.example/?q=$q")
        }
        var fallback: String? = null
        rule.setContent {
            SearchResultsPage(query = "blockedq", onOpenResult = {}, onOpenFallback = { fallback = it })
        }
        awaitNode("Search failed")
        awaitNode("upstream blocked")
        rule.onNode(hasText("Open classic search") and androidx.compose.ui.test.hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals("https://fallback.example/?q=blockedq", fallback)
    }

    @Test fun transportFailureShowsErrorAndRetry() {
        var calls = 0
        stubSearch { q, _, _, _ ->
            calls++
            if (calls < 2) throw IOException("boom")
            WebSearchResponse(kind = "text", query = q, text = listOf(WebSearchTextItem(title = "Recovered", url = "https://r.example", snippet = "")))
        }
        rule.setContent {
            SearchResultsPage(query = "flaky", onOpenResult = {}, onOpenFallback = {})
        }
        awaitNode("Search failed")
        rule.onNode(hasText("Retry") and androidx.compose.ui.test.hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        awaitNode("Recovered")
        assertTrue(calls >= 2)
    }

    @Test fun emptyResultsShowEmptyState() {
        stubSearch { q, _, _, _ -> WebSearchResponse(kind = "text", query = q, text = emptyList()) }
        rule.setContent {
            SearchResultsPage(query = "nothing", onOpenResult = {}, onOpenFallback = {})
        }
        awaitNode("No results")
    }

    @Test fun imageResultsErrorAndRetry() {
        var calls = 0
        WebSearchRepository.searchOverride = { q, _, _, _ ->
            calls++
            if (calls < 2) throw IOException("img boom")
            WebSearchResponse(
                kind = "images",
                query = q,
                images = listOf(WebSearchImageItem(title = "Dog", imageUrl = "https://img.example/dog.png", thumbnail = "https://img.example/dog-t.png", sourceUrl = "https://src.example/dog")),
            )
        }
        rule.setContent {
            SearchImageResults(query = "dogs", onOpenResult = {}, onOpenFallback = {})
        }
        awaitNode("Search failed")
        rule.onNode(hasText("Retry") and androidx.compose.ui.test.hasClickAction()).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        rule.waitForIdle()
        awaitNode("More results")
        assertTrue(calls >= 2)
    }
}
