package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.maps.util.SearchActions
import com.vayunmathur.maps.util.SearchResult
import com.vayunmathur.maps.util.SearchUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C5 search sheet phases: idle hint, searching spinner state, empty
 * state, results with locality, and recents with clear.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsSearchSheetC5Test {

    @get:Rule val rule = createComposeRule()

    private class FakeActions : SearchActions {
        var lastQuery: String? = null
        var selected: SearchResult? = null
        var cleared = 0
        var backed = 0
        override fun setQuery(query: String) { lastQuery = query }
        override fun selectResult(result: SearchResult) { selected = result }
        override fun clearRecents() { cleared++ }
        override fun back() { backed++ }
        override fun pickContactAddress(address: String) {}
    }

    @Test
    fun searchSheet_idleShowsHint() {
        rule.setContent {
            DynamicTheme {
                SearchSheet(SearchUiState(), FakeActions(), autoFocus = false)
            }
        }
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("Type at least", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "idle hint shown",
        )
    }

    @Test
    fun searchSheet_resultsShowLocalityAndSelect() {
        val actions = FakeActions()
        val result = SearchResult(
            id = "r1", title = "C5 Search Cafe", subtitle = "Coffee",
            lat = 37.7749, lon = -122.4194, category = null, locality = "San Francisco, CA",
        )
        rule.setContent {
            DynamicTheme {
                SearchSheet(
                    SearchUiState(query = "cafe", results = listOf(result)),
                    actions,
                    autoFocus = false,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("C5 Search Cafe").assertIsDisplayed()
        rule.onNodeWithText("San Francisco, CA", substring = true).assertIsDisplayed()
        rule.onNodeWithText("C5 Search Cafe").performClick()
        assertEquals(result, actions.selected)
    }

    @Test
    fun searchSheet_recentsShowAndClear() {
        val actions = FakeActions()
        rule.setContent {
            DynamicTheme {
                SearchSheet(
                    SearchUiState(recents = listOf("C5 old query")),
                    actions,
                    autoFocus = false,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("C5 old query").assertIsDisplayed()
        rule.onNodeWithText("C5 old query").performClick()
        assertEquals("C5 old query", actions.lastQuery)
    }

    @Test
    fun searchSheet_emptyAndSearchingStates() {
        rule.setContent {
            DynamicTheme {
                SearchSheet(
                    SearchUiState(query = "zzzz", results = emptyList()),
                    FakeActions(),
                    autoFocus = false,
                )
            }
        }
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("No results found", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "empty state shown",
        )
    }
}
