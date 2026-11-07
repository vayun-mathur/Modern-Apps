package com.vayunmathur.library.util

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.sdk.games.GameHubClient
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MainNavigation, bottom bar, shared-element modifiers and GameHub compose hooks under Robolectric. */
@RunWith(RobolectricTestRunner::class)
class LibraryComposeTest {

    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Serializable data class Screen(val route: String) : NavKey
    @Serializable data class Other(val route: String) : NavKey

    private class ComposeAchievements(context: Context, prefix: String) : AchievementsManager(
        context,
        """[{"id":"first_win","name":"First","description":"d","targetProgress":1}]""",
        prefix,
    ) {
        override fun checkExistingAchievements() {}
    }

    @Test fun mainNavigationRendersTopAndBottomBarAndPushes() {
        val stack = NavBackStack(arrayOf(Screen("home")))
        rule.setContent {
            MaterialTheme {
                MainNavigation(backStack = stack, bottomBar = { Text("BottomChrome") }) {
                    entry<Screen> { Text("Screen ${it.route}") }
                }
            }
        }
        rule.onNodeWithText("Screen home").assertIsDisplayed()
        rule.onNodeWithText("BottomChrome").assertIsDisplayed()
        rule.runOnIdle { stack.add(Screen("detail")) }
        rule.waitForIdle()
        rule.onNodeWithText("Screen detail").assertIsDisplayed()
        rule.runOnIdle { stack.pop() }
        rule.waitForIdle()
        rule.onNodeWithText("Screen home").assertIsDisplayed()
    }

    @Test fun mainNavigationTransparentContainer() {
        val stack = NavBackStack(arrayOf(Screen("only")))
        rule.setContent {
            MaterialTheme {
                MainNavigation(
                    backStack = stack,
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                ) {
                    entry<Screen> { Text("OnlyScreen") }
                }
            }
        }
        rule.onNodeWithText("OnlyScreen").assertIsDisplayed()
    }

    @Test fun rememberNavBackStackRetainsAndListOverload() {
        var captured: NavBackStack<Screen>? = null
        rule.setContent {
            MaterialTheme {
                val stack = rememberNavBackStack(listOf(Screen("a"), Screen("b")))
                captured = stack
                Text("Top ${stack.last().route}")
            }
        }
        rule.onNodeWithText("Top b").assertIsDisplayed()
        assertEquals(listOf("a", "b"), captured!!.backStack.map { it.route })
        rule.runOnIdle { captured!!.add(Screen("c")) }
        rule.waitForIdle()
        rule.onNodeWithText("Top c").assertIsDisplayed()
    }

    @Test fun bottomNavBarBackStackOverloadSelectsAndClicks() {
        val stack = NavBackStack(arrayOf(Screen("home")))
        var current by mutableStateOf<Screen>(Screen("home"))
        val pages = listOf(
            BottomBarItem<Screen>(name = "HomeTab", route = Screen("home"), icon = { Text("H-ic") }),
            BottomBarItem<Screen>(name = "SettingsTab", route = Screen("settings"), icon = { Text("S-ic") }),
        )
        // `current` must be observable inside composition for recomposition on change.
        rule.setContent {
            MaterialTheme {
                Column {
                    BottomNavBar(
                        backStack = stack,
                        pages = pages,
                        currentPage = current,
                        onSelect = { current = it; stack.reset(it) },
                    )
                }
            }
        }
        rule.onNodeWithText("SettingsTab").performClick()
        rule.waitForIdle()
        assertEquals("settings", stack.last().route)
        assertEquals(Screen("settings"), current)
        rule.onNodeWithText("HomeTab").performClick()
        rule.waitForIdle()
        assertEquals("home", stack.last().route)
    }

    @Test fun bottomNavBarSlotOverloadWithItems() {
        var clicks = 0
        rule.setContent {
            MaterialTheme {
                BottomNavBar {
                    BottomNavBarItem(selected = true, onClick = { clicks++ }, icon = { Text("IcA") }, label = "Alpha")
                    BottomNavBarItem(selected = false, onClick = { clicks += 10 }, icon = { Text("IcB") }, enabled = false)
                    BottomNavBarItem(selected = false, onClick = { clicks += 100 }, icon = { Text("IcC") })
                }
            }
        }
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Alpha").performClick()
        assertEquals(1, clicks)
        assertEquals(64.dp, BottomNavBarHeight)
    }

    @Test fun sharedModifiersNoOpOutsideNavigationAndFlagsFalse() {
        var morphing: Boolean? = null
        var leaving: Boolean? = null
        rule.setContent {
            MaterialTheme {
                morphing = isNavMorphing()
                leaving = isNavLeaving()
                Column {
                    Text("container", Modifier.sharedContainer("k1"))
                    Text("content", Modifier.sharedContent("k2"))
                    Text("textmorph", Modifier.sharedText("k3"))
                    Text("crop", Modifier.sharedCrop("k4"))
                    Text("expand", Modifier.expandFromLine())
                    Text("drops", Modifier.dropsAway())
                    Text("sinks", Modifier.sinksBelow())
                    Box(Modifier.fillMaxSize().onFileDrop { })
                    Text("now ${nowState().value}")
                }
            }
        }
        rule.onNodeWithText("container").assertIsDisplayed()
        rule.onNodeWithText("crop").assertIsDisplayed()
        assertEquals(false, morphing)
        assertEquals(false, leaving)
    }

    @Test fun sharedEditableTextNullAndKeyed() {
        rule.setContent {
            MaterialTheme {
                Column {
                    SharedEditableText(key = null, value = "plain") { Text("InnerPlain") }
                    SharedEditableText(key = "morph-key", value = "keyed") { Text("InnerKeyed") }
                }
            }
        }
        rule.onNodeWithText("InnerPlain").assertIsDisplayed()
        rule.onNodeWithText("InnerKeyed").assertIsDisplayed()
        rule.onNodeWithText("keyed").assertExists()
    }

    @Test fun gameHubHooksComposeAndClientNotInstalled() {
        var client: GameHubClient? = null
        val manager = ComposeAchievements(ctx, "compose${System.nanoTime()}_")
        rule.setContent {
            MaterialTheme {
                client = rememberGameHubClient("chess")
                Column {
                    GameHubComposeHook(gameId = "chess", achievementsManager = manager)
                    GameHubSessionHook(gameId = "sudoku", displayName = "Sudoku")
                    Text("HooksHost")
                }
            }
        }
        rule.onNodeWithText("HooksHost").assertIsDisplayed()
        rule.waitForIdle()
        assertNotNull(client)
        assertFalse(client!!.isHubInstalled())
    }

    @Test fun gameHubHookWithoutManager() {
        rule.setContent {
            MaterialTheme {
                Column {
                    GameHubComposeHook(gameId = "unknown_game_xyz", achievementsManager = null)
                    Text("NoManagerHost")
                }
            }
        }
        rule.onNodeWithText("NoManagerHost").assertIsDisplayed()
    }

    @Test fun entryProviderScopeMatchesOnlyItsType() {
        val hit = EntryProviderScope<NavKey>(Screen("a"))
        hit.entry<Screen> { Text("unused") }
        assertNotNull(hit.result)
        val miss = EntryProviderScope<NavKey>(Screen("a"))
        miss.entry<Other> { Text("unused") }
        assertNull(miss.result)
    }

    @Test fun pageHelpersDeclareMotions() {
        assertEquals(NavMotion.Detail, navMotionIn(DetailPage()))
        assertEquals(NavMotion.Zoom, navMotionIn(ZoomPage()))
        assertEquals(NavMotion.Fullscreen, navMotionIn(FullscreenPage()))
        assertEquals(NavMotion.Sibling, navMotionIn(SiblingPage()))
        assertEquals(NavMotion.Morph, navMotionIn(MorphPage()))
        assertEquals(NavMotion.Detail, navMotionIn(null))
        assertEquals(NavMotion.Detail, navMotionIn(emptyMap()))
        assertEquals(NavMotion.Detail, navMotionIn(mapOf(NAV_MOTION_KEY to "not-a-motion")))
        assertTrue(DialogPage().isNotEmpty())
        assertTrue(ListDetailPage().isNotEmpty())
    }

    @Test fun nowStateProducesInstant() {
        var seen: String? = null
        rule.setContent { seen = nowState().value.toString() }
        rule.waitForIdle()
        assertTrue(seen != null && seen!!.contains("T"), "instant string: $seen")
    }
}
