package com.vayunmathur.library.util

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** MainNavigation transition specs per motion, dialog entries, morphs, message drain. */
@RunWith(RobolectricTestRunner::class)
class LibraryCompose2Test {

    @get:Rule val rule = createComposeRule()

    @Serializable data class Screen(val route: String) : NavKey

    private fun motionFor(route: String): Map<String, Any> = when (route) {
        "zoom" -> ZoomPage()
        "full" -> FullscreenPage()
        "sibling" -> SiblingPage()
        "morph" -> MorphPage()
        "dialog" -> DialogPage()
        else -> DetailPage()
    }

    @Test fun navigationPushPopAcrossAllMotions() {
        val stack = NavBackStack(arrayOf(Screen("home")))
        rule.setContent {
            MaterialTheme {
                MainNavigation(backStack = stack) {
                    entry<Screen>(metadata = motionFor(obj.route)) { screen ->
                        if (screen.route == "morph") {
                            Text("MorphTarget", Modifier.sharedContainer("shared-1"))
                        } else {
                            Text("Page ${screen.route}")
                        }
                    }
                }
            }
        }
        rule.onNodeWithText("Page home").assertIsDisplayed()
        for (route in listOf("zoom", "full", "sibling", "morph")) {
            rule.runOnIdle { stack.add(Screen(route)) }
            rule.waitForIdle()
            if (route == "morph") {
                rule.onNodeWithText("MorphTarget").assertExists()
            } else {
                rule.onNodeWithText("Page $route").assertExists()
            }
            rule.runOnIdle { stack.pop() }
            rule.waitForIdle()
            rule.onNodeWithText("Page home").assertIsDisplayed()
        }
        assertEquals(1, stack.backStack.size)
    }

    @Test fun dialogEntryShowsAsDialog() {
        val stack = NavBackStack(arrayOf(Screen("home")))
        rule.setContent {
            MaterialTheme {
                MainNavigation(backStack = stack) {
                    entry<Screen>(metadata = motionFor(obj.route)) { screen ->
                        Text("Page ${screen.route}")
                    }
                }
            }
        }
        rule.runOnIdle { stack.add(Screen("dialog")) }
        rule.waitForIdle()
        rule.onNodeWithText("Page dialog").assertExists()
        rule.runOnIdle { stack.pop() }
        rule.waitForIdle()
        rule.onNodeWithText("Page home").assertIsDisplayed()
    }

    @Test fun messageDrainConsumesPostedMessages() {
        val stack = NavBackStack(arrayOf(Screen("home")))
        rule.setContent {
            MaterialTheme {
                MainNavigation(backStack = stack) {
                    entry<Screen> { Text("DrainHome") }
                }
            }
        }
        rule.onNodeWithText("DrainHome").assertIsDisplayed()
        // Posted from outside composition, as a ViewModel/worker would. The
        // drain collects it onto the (host-owned) snackbar state.
        AppMessages.show("drain-check-${System.nanoTime()}", actionLabel = "Undo") { }
        rule.waitForIdle()
        rule.onNodeWithText("DrainHome").assertIsDisplayed()
    }

    @Test fun sharedModifiersInsideNavigationMorph() {
        val stack = NavBackStack(arrayOf(Screen("home")))
        rule.setContent {
            MaterialTheme {
                MainNavigation(backStack = stack) {
                    entry<Screen>(metadata = motionFor(obj.route)) { screen ->
                        Column {
                            Text("Head ${screen.route}", Modifier.sharedContainer("head"))
                            Text("Leaf ${screen.route}", Modifier.sharedContent("leaf"))
                            Text("Words ${screen.route}", Modifier.sharedText("words"))
                            Text("Crop ${screen.route}", Modifier.sharedCrop("crop"))
                            Text("Expand ${screen.route}", Modifier.expandFromLine())
                            Text("Drop ${screen.route}", Modifier.dropsAway())
                            Text("Sink ${screen.route}", Modifier.sinksBelow())
                            Text("Morphing ${isNavMorphing()} leaving ${isNavLeaving()}")
                        }
                    }
                }
            }
        }
        rule.onNodeWithText("Head home").assertIsDisplayed()
        rule.runOnIdle { stack.add(Screen("morph")) }
        rule.waitForIdle()
        rule.onNodeWithText("Head morph").assertExists()
        rule.onNodeWithText("Crop morph").assertExists()
        rule.runOnIdle { stack.pop() }
        rule.waitForIdle()
        rule.onNodeWithText("Head home").assertIsDisplayed()
        // A Detail push (screen still leaves) also composes the exit helpers.
        rule.runOnIdle { stack.add(Screen("zoom")) }
        rule.waitForIdle()
        rule.onNodeWithText("Head zoom").assertExists()
    }

    @Test fun gameHubHooksResolveEveryDisplayName() {
        val ids = listOf(
            "chess", "solitaire", "pipes", "unblockjam", "wordmaker", "logicgate",
            "sudoku", "minesweeper", "nonogram", "arrows", "kart", "brand_new_game",
        )
        rule.setContent {
            MaterialTheme {
                Column {
                    ids.forEach { id -> GameHubComposeHook(gameId = id, achievementsManager = null) }
                    Text("AllHooks")
                }
            }
        }
        rule.onNodeWithText("AllHooks").assertIsDisplayed()
        rule.waitForIdle()
    }
}
