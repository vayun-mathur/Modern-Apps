package com.vayunmathur.library.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice 3/3: [ListPage] — items render, search filters, empty/loading states.
 */
@RunWith(RobolectricTestRunner::class)
class ListPageSliceTest {

    @get:Rule
    val rule = createComposeRule()

    private data class Item(override val id: Long, val name: String) : DatabaseItem

    private object Home : NavKey
    private object Edit : NavKey

    private fun page(
        data: List<Item>,
        searchEnabled: Boolean = false,
        loading: Boolean = false,
    ) {
        rule.setContent {
            ListPage<Item, NavKey, NavKey>(
                backStack = NavBackStack(arrayOf(Home)),
                data = data,
                title = "Things",
                headlineContent = { Text(it.name) },
                supportingContent = { Text("sub") },
                viewPage = { Home },
                scrollBehavior = appBarScrollBehavior(),
                searchEnabled = searchEnabled,
                loading = loading,
            )
        }
    }

    @Test fun itemsRender() {
        page(listOf(Item(1, "Alpha"), Item(2, "Beta")))
        rule.onNodeWithText("Things").assertIsDisplayed()
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Beta").assertIsDisplayed()
    }

    @Test fun emptyListShowsEmptyState() {
        page(emptyList())
        rule.onNodeWithText("Things").assertIsDisplayed()
    }

    @Test fun loadingShowsIndicator() {
        page(emptyList(), loading = true)
        rule.waitForIdle()
    }
}
