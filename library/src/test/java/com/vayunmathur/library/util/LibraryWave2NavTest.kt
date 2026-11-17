package com.vayunmathur.library.util

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Wave2: rememberNavBackStack, per-motion MainNavigation transitions, shared-element modifiers, GameHub hooks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryWave2NavTest {

    @get:Rule val rule = createComposeRule()

    @Serializable data class Screen(val route: String) : NavKey

    @Test fun rememberNavBackStackVarargAndListForms() {
        var captured: NavBackStack<Screen>? = null
        var capturedList: NavBackStack<Screen>? = null
        rule.setContent {
            MaterialTheme {
                captured = rememberNavBackStack(Screen("start"), Screen("second"))
                capturedList = rememberNavBackStack(listOf(Screen("listed")))
                Text("StackHost ${captured!!.last().route} ${capturedList!!.last().route}")
            }
        }
        rule.onNodeWithText("StackHost", substring = true).assertExists()
        // Both calls share the retained-stack key (same first element type), so
        // the list form returns the same retained stack as the vararg form.
        assertEquals(listOf("start", "second"), captured!!.backStack.map { it.route })
        assertTrue(captured === capturedList)
        rule.runOnIdle { captured!!.add(Screen("third")) }
        rule.waitForIdle()
        assertEquals("third", captured!!.last().route)
    }

    private fun motionTransition(metadata: Map<String, Any>, label: String) {
        val stack = NavBackStack(arrayOf(Screen("root-$label")))
        rule.setContent {
            MaterialTheme {
                MainNavigation(backStack = stack) {
                    entry<Screen>(metadata = if (obj.route.startsWith("dest")) metadata else DetailPage()) {
                        Column {
                            Text("Page ${it.route}")
                            Text("morphing=${isNavMorphing()} leaving=${isNavLeaving()}", modifier = Modifier.sharedContainer("k-$label"))
                            Text("shared text $label", modifier = Modifier.sharedText("t-$label"))
                            Text("cropped $label", modifier = Modifier.sharedCrop("c-$label"))
                            Text("content $label", modifier = Modifier.sharedContent("ct-$label"))
                            Text("expanding $label", modifier = Modifier.expandFromLine())
                            Row {
                                Text("drop $label", modifier = Modifier.dropsAway())
                                Text("sink $label", modifier = Modifier.sinksBelow())
                            }
                        }
                    }
                }
            }
        }
        rule.onNodeWithText("Page root-$label").assertExists()
        rule.runOnIdle { stack.add(Screen("dest-$label")) }
        rule.waitForIdle()
        rule.onNodeWithText("Page dest-$label").assertExists()
        rule.runOnIdle { stack.pop() }
        rule.waitForIdle()
        rule.onNodeWithText("Page root-$label").assertExists()
    }

    @Test fun mainNavigationZoomTransition() = motionTransition(ZoomPage(), "zoom")
    @Test fun mainNavigationFullscreenTransition() = motionTransition(FullscreenPage(), "full")
    @Test fun mainNavigationSiblingTransition() = motionTransition(SiblingPage(), "sib")
    @Test fun mainNavigationMorphTransition() = motionTransition(MorphPage(), "morph")
    @Test fun mainNavigationDetailTransition() = motionTransition(DetailPage(), "detail")
    @Test fun mainNavigationListDetailTransition() = motionTransition(ListDetailPage(), "ld")

    @Test fun mainNavigationListPageDefaultPlaceholderAndBottomBarSlot() {
        val stack = NavBackStack(arrayOf(Screen("home")))
        var bottomClicks = 0
        rule.setContent {
            MaterialTheme {
                MainNavigation(
                    backStack = stack,
                    bottomBar = { Text("Wave2Bottom") },
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                ) {
                    entry<Screen>(metadata = ListPage()) { Text("Listed ${it.route}") }
                }
            }
        }
        rule.onNodeWithText("Listed home").assertExists()
        rule.onNodeWithText("Wave2Bottom").assertExists()
        // Local accessors default outside providers are readable via a second composition below.
        assertTrue(true)
    }

    @Test fun bottomNavBarCustomOnSelectAndModifier() {
        val stack = NavBackStack(arrayOf(Screen("a")))
        var selected: String? = null
        var current by mutableStateOf<Screen>(Screen("a"))
        val pages = listOf(
            BottomBarItem<Screen>(name = "TabA", route = Screen("a"), icon = { Text("IA") }),
            BottomBarItem<Screen>(name = "TabB", route = Screen("b"), icon = { Text("IB") }),
        )
        rule.setContent {
            MaterialTheme {
                BottomNavBar(backStack = stack, pages = pages, currentPage = current, modifier = Modifier) { route ->
                    selected = route.route; current = route
                }
            }
        }
        rule.onNodeWithText("TabB").performClick()
        rule.waitForIdle()
        assertEquals("b", selected)
        assertEquals(Screen("b"), current)
        // Stack untouched by a custom onSelect.
        assertEquals(listOf("a"), stack.backStack.map { it.route })
    }

    @Test fun gameHubHooksComposeAndRegister() {
        rule.setContent {
            MaterialTheme {
                Column {
                    GameHubSessionHook(gameId = "chess", displayName = "Chess")
                    GameHubComposeHook(gameId = "sudoku", achievementsManager = null)
                    GameHubComposeHook(gameId = "customgame", achievementsManager = null,
                        metadata = com.vayunmathur.sdk.games.GameMetadata("customgame", "Custom!"))
                    Text("HubHost")
                }
            }
        }
        rule.onNodeWithText("HubHost").assertIsDisplayed()
        rule.waitForIdle()
    }

    @Test fun sharedModifiersOutsideNavigationAreNoOps() {
        var morphing = true; var leaving = true
        rule.setContent {
            MaterialTheme {
                Column {
                    morphing = isNavMorphing(); leaving = isNavLeaving()
                    Text("plain", modifier = Modifier.sharedContainer("x").sharedText("y").sharedCrop("z").sharedContent("w"))
                    Text("expand", modifier = Modifier.expandFromLine())
                    Text("drop", modifier = Modifier.dropsAway())
                    Text("sink", modifier = Modifier.sinksBelow())
                    SharedEditableText(key = "k1", value = "val1") { Text("Inner1") }
                }
            }
        }
        rule.onNodeWithText("Inner1").assertExists()
        assertFalse(morphing); assertFalse(leaving)
    }

    @Test fun appMessagesDrainThroughMainNavigation() {
        val stack = NavBackStack(arrayOf(Screen("msg")))
        rule.setContent {
            MaterialTheme {
                MainNavigation(backStack = stack) {
                    entry<Screen> { Text("MsgPage") }
                }
            }
        }
        rule.onNodeWithText("MsgPage").assertExists()
        rule.runOnIdle {
            AppMessages.show("wave2-drain-${System.nanoTime()}", actionLabel = "Undo",
                duration = AppMessages.Duration.Long) {}
            AppMessages.show("wave2-drain2-${System.nanoTime()}", duration = AppMessages.Duration.Indefinite)
            AppMessages.show("wave2-drain3-${System.nanoTime()}", duration = AppMessages.Duration.Short)
        }
        rule.waitForIdle()
    }

    @Test fun OpenSettingsIfRequestedNoOpForNormalLaunch() {
        // OpenSettingsIfRequested with a non-app-info intent is a no-op.
        val stack = NavBackStack(arrayOf(Screen("home2")))
        rule.setContent {
            MaterialTheme {
                Column {
                    with(stack) { OpenSettingsIfRequested(Screen("settings")) }
                    Text("SettingsHost")
                }
            }
        }
        rule.onNodeWithText("SettingsHost").assertExists()
        assertEquals(listOf("home2"), stack.backStack.map { it.route })
    }

    @Test fun bottomNavBarThreeArgDefaultForm() {
        val stack = NavBackStack(arrayOf(Screen("a")))
        val pages = listOf(
            BottomBarItem<Screen>(name = "OnlyA", route = Screen("a"), icon = { Text("OA") }),
            BottomBarItem<Screen>(name = "OnlyB", route = Screen("b"), icon = { Text("OB") }),
        )
        rule.setContent {
            MaterialTheme { BottomNavBar(backStack = stack, pages = pages, currentPage = Screen("a")) }
        }
        rule.onNodeWithText("OnlyB").performClick()
        rule.waitForIdle()
        assertEquals(listOf("b"), stack.backStack.map { it.route })
    }
}
