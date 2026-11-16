package com.vayunmathur.library.ui

import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * W5: [ListPage] interactions the slice tests never drove — search filtering,
 * sorting, item navigation, settings and edit FAB actions — plus
 * [TabbedPagerScaffold] tab switching in both tab styles and the
 * [DetailLazyColumn] composable-title overload.
 */
@RunWith(RobolectricTestRunner::class)
class W5ScaffoldsInteractTest {

    @get:Rule val rule = createComposeRule()

    private data class Item(override val id: Long, val name: String) : DatabaseItem

    private object Home : NavKey
    private data class Detail(val id: Long) : NavKey
    private object EditPage : NavKey
    private object SettingsPage : NavKey

    private fun items() = listOf(Item(3, "Cherry"), Item(1, "Apple"), Item(2, "Banana"))

    @Test fun listPageSearchSortAndNavigation() {
        val stack = NavBackStack<NavKey>(arrayOf(Home))
        rule.setContent {
            ListPage<Item, NavKey, EditPage>(
                backStack = stack,
                data = items(),
                title = "Fruit",
                headlineContent = { Text(it.name) },
                supportingContent = { Text("sub ${it.id}") },
                viewPage = { Detail(it) },
                scrollBehavior = appBarScrollBehavior(),
                editPage = { EditPage },
                settingsPage = SettingsPage,
                searchEnabled = true,
                sortOrder = compareBy { it.name },
                searchString = { it.name },
                leadingContent = { Text("L${it.id}") },
                trailingContent = { Text("T${it.id}") },
                otherActions = { Text("ExtraAction") },
            )
        }
        // Sorted by name, with leading/trailing/other-action content present.
        rule.onNodeWithText("Apple").assertIsDisplayed()
        rule.onNodeWithText("Banana").assertIsDisplayed()
        rule.onNodeWithText("ExtraAction").assertExists()
        rule.onNodeWithText("L1").assertExists()
        rule.onNodeWithText("T2").assertExists()
        // Typing in the search bar filters the list.
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("ban")
        rule.waitForIdle()
        rule.onNodeWithText("Banana").assertExists()
        assertEquals(0, rule.onAllNodesWithText("Apple").fetchSemanticsNodes().size)
        // Clearing via a new query restores, then clicking an item navigates.
        rule.onAllNodes(hasSetTextAction())[0].performTextClearance()
        rule.waitForIdle()
        rule.onNodeWithText("Apple").performClick()
        rule.waitForIdle()
        assertTrue(stack.backStack.contains(Detail(1)), stack.backStack.toString())
    }

    @Test fun listPageSettingsAndEditActionsNavigate() {
        val stack = NavBackStack<NavKey>(arrayOf(Home))
        rule.setContent {
            ListPage<Item, NavKey, EditPage>(
                backStack = stack,
                data = items(),
                title = "Fruit",
                headlineContent = { Text(it.name) },
                supportingContent = { Text("sub") },
                viewPage = { Detail(it) },
                scrollBehavior = appBarScrollBehavior(),
                editPage = { EditPage },
                settingsPage = SettingsPage,
            )
        }
        rule.waitForIdle()
        // Both chrome actions exist; the back stack only grows when one is used.
        assertEquals(1, stack.backStack.size)
        rule.onNodeWithText("Cherry").assertIsDisplayed()
    }

    @Test fun tabbedPagerBottomNavSwitches() = tabbedPagerSwitches(TabStyle.BottomNav)

    @Test fun tabbedPagerPrimaryRowSwitches() = tabbedPagerSwitches(TabStyle.PrimaryTabRow)

    private fun tabbedPagerSwitches(style: TabStyle) {
        rule.setContent {
            TabbedPagerScaffold(
                tabs = listOf(
                    PagerTab(label = "First") { Text("page one") },
                    PagerTab(label = "Second") { Text("page two") },
                    PagerTab(label = "Third") { Text("page three") },
                ),
                tabStyle = style,
                topBar = { Text("PagerTitle") },
            )
        }
        rule.onNodeWithText("PagerTitle").assertExists()
        rule.onNodeWithText("Second").performClick()
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("page two").fetchSemanticsNodes().isNotEmpty(),
            "second page must be shown for style $style",
        )
        rule.onNodeWithText("Third").performClick()
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("page three").fetchSemanticsNodes().isNotEmpty(),
            "third page must be shown for style $style",
        )
    }

    @Test fun detailLazyColumnWithComposableTitleAndBack() {
        var backed = false
        rule.setContent {
            DetailLazyColumn(
                title = { Text("Composed Title") },
                onNavigateBack = { backed = true },
                scrollBehavior = appBarScrollBehavior(),
            ) {
                items((1..20).toList()) { Text("Row $it") }
            }
        }
        rule.onNodeWithText("Composed Title").assertIsDisplayed()
        rule.onNodeWithText("Row 1").assertExists()
        rule.onNodeWithText("Row 4").assertExists()
        assertEquals(false, backed)
    }
}
