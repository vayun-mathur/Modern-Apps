package com.vayunmathur.library.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Route-backed [TabbedPagerScaffold]: selecting a tab resets the back stack
 * to that tab's route (SiblingPage semantics) and the content slot renders
 * the destination now on top of the stack.
 */
@RunWith(RobolectricTestRunner::class)
class RouteTabbedPagerScaffoldTest {

    @get:Rule val rule = createComposeRule()

    private object First : NavKey
    private object Second : NavKey
    private data class Detail(val id: Long) : NavKey

    private fun setRouteScaffold(stack: NavBackStack<NavKey>) {
        rule.setContent {
            TabbedPagerScaffold(
                backStack = stack,
                tabs = listOf(
                    RouteTab(First, "First"),
                    RouteTab(Second, "Second"),
                ),
            ) { route ->
                when (route) {
                    First -> Text("Page one")
                    Second -> Text("Page two")
                    is Detail -> Text("Detail page")
                    else -> Text("Other page")
                }
            }
        }
    }

    @Test fun rendersTopRouteAndTabSelectResetsStack() {
        // A detail destination pushed over the first tab: selecting a tab
        // must reset (replace) the whole stack, not push.
        val stack = NavBackStack<NavKey>(arrayOf(First, Detail(7)))
        setRouteScaffold(stack)
        rule.onNodeWithText("Detail page").assertIsDisplayed()

        rule.onNodeWithText("Second").performClick()
        rule.waitForIdle()

        assertEquals(listOf(Second), stack.backStack)
        rule.onNodeWithText("Page two").assertIsDisplayed()
    }

    @Test fun tabSelectFromTabReplacesAndRendersDestination() {
        val stack = NavBackStack<NavKey>(arrayOf(First))
        setRouteScaffold(stack)
        rule.onNodeWithText("Page one").assertIsDisplayed()

        rule.onNodeWithText("Second").performClick()
        rule.waitForIdle()
        assertEquals(listOf(Second), stack.backStack)
        rule.onNodeWithText("Page two").assertIsDisplayed()

        rule.onNodeWithText("First").performClick()
        rule.waitForIdle()
        assertEquals(listOf(First), stack.backStack)
        rule.onNodeWithText("Page one").assertIsDisplayed()
    }

    @Test fun reselectingCurrentTabIsNoOp() {
        val stack = NavBackStack<NavKey>(arrayOf(First))
        setRouteScaffold(stack)

        rule.onNodeWithText("First").performClick()
        rule.waitForIdle()

        assertEquals(listOf(First), stack.backStack)
        rule.onNodeWithText("Page one").assertIsDisplayed()
    }
}
