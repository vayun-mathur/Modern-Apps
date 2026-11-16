package com.vayunmathur.library.tv

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.tv.material3.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Route-backed [TvAppFrame]: selecting a rail destination resets the back
 * stack to that destination's route (sibling semantics) and the content slot
 * renders the destination now on top of the stack.
 */
@RunWith(RobolectricTestRunner::class)
class TvRouteAppFrameTest {

    @get:Rule val rule = createComposeRule()

    private object Home : NavKey
    private object Films : NavKey
    private data class Detail(val id: Long) : NavKey

    private fun clickText(text: String) {
        // A TV rail item is focused before it is clicked, and focusing it is
        // what selects it in a real session — so focus first, then click,
        // instead of firing OnClick on an unfocused item (whose
        // move-focus-into-content would start from no focus and land on the
        // first rail item).
        val node = rule.onNode(androidx.compose.ui.test.hasText(text))
        node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        rule.waitForIdle()
        val ok = node.fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action?.invoke()
        assertTrue(ok == true, "no click action for '$text'")
    }

    private fun setRouteFrame(stack: NavBackStack<NavKey>) {
        rule.setContent {
            MaTvTheme(Color(0xFF3366FF)) {
                TvAppFrame(
                    backStack = stack,
                    destinations = listOf(
                        TvRouteDestination(Home, "Home") { Text("H") },
                        TvRouteDestination(Films, "Films") { Text("F") },
                    ),
                ) { route ->
                    // Focusable, like real TV content (a grid/button): the
                    // rail's click moves focus right into the content, and
                    // with nothing focusable there it would hunt back to the
                    // rail and focus-select the first destination.
                    Box(Modifier.focusable()) {
                        when (route) {
                            Home -> Text("Home content")
                            Films -> Text("Films content")
                            is Detail -> Text("Detail content")
                            else -> Text("Other content")
                        }
                    }
                }
            }
        }
    }

    @Test fun rendersTopRouteAndRailSelectResetsStack() {
        // A detail destination pushed over Home: selecting a rail destination
        // must reset (replace) the whole stack, not push.
        val stack = NavBackStack<NavKey>(arrayOf(Home, Detail(7)))
        setRouteFrame(stack)
        rule.onNodeWithText("Detail content").assertExists()
        rule.onNodeWithText("Home").assertExists()
        rule.waitForIdle()

        clickText("Films")
        rule.waitForIdle()

        assertEquals(listOf(Films), stack.backStack)
        rule.onNodeWithText("Films content").assertExists()
    }

    @Test fun railSelectFromDestinationReplacesAndRenders() {
        val stack = NavBackStack<NavKey>(arrayOf(Home))
        setRouteFrame(stack)
        rule.onNodeWithText("Home content").assertExists()

        clickText("Films")
        rule.waitForIdle()
        assertEquals(listOf(Films), stack.backStack)
        rule.onNodeWithText("Films content").assertExists()

        clickText("Home")
        rule.waitForIdle()
        assertEquals(listOf(Home), stack.backStack)
        rule.onNodeWithText("Home content").assertExists()
    }
}

