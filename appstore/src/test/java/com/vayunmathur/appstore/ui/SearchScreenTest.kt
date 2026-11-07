package com.vayunmathur.appstore.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.util.SearchActions
import com.vayunmathur.appstore.util.SearchUiState
import com.vayunmathur.appstore.util.SourceFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Search screen states: the prompt, results, no-results, the Play-error
 * line and the source filter chips. Stateless (state + actions), so every
 * branch renders directly.
 */
@RunWith(RobolectricTestRunner::class)
class SearchScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app(pkg: String, name: String) = UnifiedApp(
        packageName = pkg,
        source = AppSource.FDROID,
        name = name,
        summary = "Summary of $name",
    )

    @Test
    fun `typing forwards the query`() {
        var query = ""
        val actions = object : SearchActions {
            override fun setSearch(q: String) {
                query = q
            }
        }
        rule.setContent { SearchScreen(state = SearchUiState(), actions = actions) }

        rule.onNodeWithText("Search for an app").performTextInput("sign")
        rule.waitForIdle()
        assertEquals("sign", query)
    }

    @Test
    fun `results render and forward taps`() {
        var opened: UnifiedApp? = null
        val state = SearchUiState(
            query = "sign",
            results = listOf(app("com.example.a", "Alpha"), app("com.example.b", "Beta")),
            hasSearched = true,
        )
        rule.setContent { SearchScreen(state = state, actions = SearchActions.Noop, onAppClick = { opened = it }) }

        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Beta").assertIsDisplayed()
        rule.onNodeWithText("Beta").performClick()
        assertEquals("com.example.b", opened?.packageName)
    }

    @Test
    fun `no results after a search shows the empty state`() {
        val state = SearchUiState(query = "zzz-no-such-app", hasSearched = true)
        rule.setContent { SearchScreen(state = state, actions = SearchActions.Noop) }

        // The empty-state title quotes the query; the search field holds it too,
        // so match the full title exactly.
        rule.onNodeWithText("No results for \"zzz-no-such-app\"").assertIsDisplayed()
    }

    @Test
    fun `play error line shows above the results`() {
        val state = SearchUiState(
            query = "sign",
            results = listOf(app("com.example.a", "Alpha")),
            hasSearched = true,
            playError = "Play is rate-limited",
        )
        rule.setContent { SearchScreen(state = state, actions = SearchActions.Noop) }

        rule.onNodeWithText("Play is rate-limited").assertIsDisplayed()
        rule.onNodeWithText("Alpha").assertIsDisplayed()
    }

    @Test
    fun `filter chips forward the selection`() {
        var filter: SourceFilter? = null
        val actions = object : SearchActions {
            override fun setSearchFilter(f: SourceFilter) {
                filter = f
            }
        }
        rule.setContent { SearchScreen(state = SearchUiState(query = "x"), actions = actions) }

        rule.onNodeWithText("F-Droid").performClick()
        assertEquals(SourceFilter.FDROID, filter)
    }

    @Test
    fun `every filter label resolves`() {
        for (filter in SourceFilter.entries) {
            filter.labelRes()
        }
    }
}
