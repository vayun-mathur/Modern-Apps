package com.vayunmathur.library.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Scaffold-owned search: the `search` parameter renders the shared field AS
 * the top app bar (title slot, title replaced), query edits flow to the
 * caller, the clear affordance empties the query, and the default (no
 * search) renders the title with no field.
 */
@RunWith(RobolectricTestRunner::class)
class ScaffoldSearchSliceTest {

    @get:Rule
    val rule = createComposeRule()

    @Test fun appScaffoldSearchRendersAndQueryFlows() {
        rule.setContent {
            var query by remember { mutableStateOf("") }
            AppScaffold(
                title = "Inbox",
                search = ScaffoldSearch(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "Search mail",
                ),
                scrollBehavior = appBarScrollBehavior(),
            ) { Text("Body text") }
        }
        // Search replaces the title in the bar's title slot.
        rule.onNodeWithText("Inbox").assertDoesNotExist()
        rule.onNodeWithText("Search mail").assertIsDisplayed()
        rule.onNodeWithText("Search mail").performTextInput("hello")
        // The typed text replaces the placeholder once the query flows back in.
        rule.onNodeWithText("hello").assertIsDisplayed()
    }

    @Test fun appScaffoldWithoutSearchHasNoField() {
        rule.setContent {
            AppScaffold(
                title = "Inbox",
                scrollBehavior = appBarScrollBehavior(),
            ) { Text("Body text") }
        }
        rule.onNodeWithText("Inbox").assertIsDisplayed()
        rule.onNodeWithText("Search").assertDoesNotExist()
    }

    @Test fun scaffoldSearchClearEmptiesQuery() {
        var last = "seed"
        rule.setContent {
            var query by remember { mutableStateOf("seed") }
            AppScaffold(
                title = "Notes",
                search = ScaffoldSearch(
                    query = query,
                    onQueryChange = { query = it; last = it },
                    placeholder = "Search notes",
                ),
                scrollBehavior = appBarScrollBehavior(),
            ) { Text("B") }
        }
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals("", last)
    }

    @Test fun lazyListScaffoldSearchRendersAboveList() {
        rule.setContent {
            var query by remember { mutableStateOf("") }
            LazyListScaffold(
                title = "Countries",
                search = ScaffoldSearch(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "Search countries",
                ),
            ) {
                items(listOf("France", "Spain")) { Text(it) }
            }
        }
        rule.onNodeWithText("Countries").assertDoesNotExist()
        rule.onNodeWithText("Search countries").assertIsDisplayed()
        rule.onNodeWithText("France").assertIsDisplayed()
    }

    @Test fun detailScaffoldSearchRenders() {
        rule.setContent {
            var query by remember { mutableStateOf("") }
            DetailScaffold(
                title = "Picker",
                search = ScaffoldSearch(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "Search items",
                ),
                scrollBehavior = appBarScrollBehavior(),
            ) { Text("Row") }
        }
        rule.onNodeWithText("Search items").assertIsDisplayed()
        rule.onNodeWithText("Row").assertIsDisplayed()
    }
}
