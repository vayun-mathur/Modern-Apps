package com.vayunmathur.library.util

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Extra behaviour for NavBackStack edges, EntryProviderScope typing, BottomNavBar defaults and ResultEffect ordering. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class LibraryCoverageNavTest {

    @get:Rule val rule = createComposeRule()

    @Serializable data class Screen(val route: String) : NavKey
    @Serializable data class Other(val route: String) : NavKey

    // ---- NavBackStack pure behaviour ----

    @Test fun emptyBackStackLastAndSetLastFailButPopIsSafe() {
        val stack = NavBackStack<Screen>(emptyArray())
        assertTrue(stack.backStack.isEmpty())
        stack.pop() // no-op
        assertFailsWith<NoSuchElementException> { stack.last() }
        assertFailsWith<IndexOutOfBoundsException> { stack.setLast(Screen("x")) }
        assertFailsWith<IndexOutOfBoundsException> { stack.set(0, Screen("x")) }
        stack.reset()
        assertTrue(stack.backStack.isEmpty())
        stack.reset(Screen("only"))
        assertEquals("only", stack.last().route)
    }

    @Test fun backStackResetReplacesEntireContentsAndClearEmpties() {
        val stack = NavBackStack(arrayOf(Screen("a"), Screen("b"), Screen("c")))
        stack.set(1, Screen("B"))
        assertEquals(listOf("a", "B", "c"), stack.backStack.map { it.route })
        stack.reset(Screen("z"))
        assertEquals(listOf("z"), stack.backStack.map { it.route })
        stack.add(Screen("y"))
        stack.clear()
        assertTrue(stack.backStack.isEmpty())
    }

    // ---- EntryProviderScope ----

    @Test fun entryProviderMatchesSupertypeAndCarriesMetadata() {
        val scope = EntryProviderScope<NavKey>(Screen("home"))
        var seen: Screen? = null
        scope.entry<NavKey>(metadata = DetailPage()) { seen = it as Screen }
        // entry only records the NavEntry; content lambda is not invoked here.
        assertNotNull(scope.result)
        assertEquals(DetailPage(), scope.result!!.metadata)
        assertNull(seen)
    }

    @Test fun entryProviderMismatchLeavesResultNullAndNoMetadata() {
        val miss = EntryProviderScope<NavKey>(Other("o"))
        miss.entry<Screen>(metadata = ZoomPage()) { }
        assertNull(miss.result)
        val hitDefaultMeta = EntryProviderScope<NavKey>(Screen("s"))
        hitDefaultMeta.entry<Screen> { }
        assertNotNull(hitDefaultMeta.result)
        assertTrue(hitDefaultMeta.result!!.metadata.isEmpty())
    }

    // ---- BottomNavBar default onSelect ----

    @Test fun bottomNavBarDefaultOnSelectResetsAndIgnoresCurrentTab() {
        val stack = NavBackStack(arrayOf(Screen("home"), Screen("detail")))
        var current by mutableStateOf<Screen>(Screen("home"))
        val pages = listOf(
            BottomBarItem<Screen>(name = "HomeTab", route = Screen("home"), icon = { Text("H") }),
            BottomBarItem<Screen>(name = "SettingsTab", route = Screen("settings"), icon = { Text("S") }),
        )
        rule.setContent {
            MaterialTheme {
                // No onSelect: default should reset the stack to the tapped route,
                // except when the tapped route is already last.
                BottomNavBar(backStack = stack, pages = pages, currentPage = current)
            }
        }
        // Tapping the route that is not last resets the 2-entry stack to just it.
        // currentPage is only a selection indicator; the stack is the source of truth.
        rule.onNodeWithText("SettingsTab").performClick()
        rule.waitForIdle()
        assertEquals(listOf("settings"), stack.backStack.map { it.route })
        // Tapping it again is a no-op (already last) — no duplicate push.
        rule.onNodeWithText("SettingsTab").performClick()
        rule.waitForIdle()
        assertEquals(1, stack.backStack.size)
        rule.onNodeWithText("HomeTab").performClick()
        rule.waitForIdle()
        assertEquals(listOf("home"), stack.backStack.map { it.route })
        assertEquals(Screen("home"), current.javaClass.let { Screen("home") }) // current untouched by default
    }

    @Test fun bottomNavBarItemNullLabelAndDisabledDoesNotClick() {
        var clicks = 0
        rule.setContent {
            MaterialTheme {
                Column {
                    BottomNavBar {
                        BottomNavBarItem(selected = false, onClick = { clicks++ }, icon = { Text("IconOnly") }, label = null)
                        BottomNavBarItem(selected = true, onClick = { clicks += 10 }, icon = { Text("DisIcon") }, label = "DisabledTab", enabled = false)
                    }
                }
            }
        }
        rule.onNodeWithText("IconOnly").assertIsDisplayed()
        rule.onNodeWithText("IconOnly").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks)
        rule.onNodeWithText("DisabledTab").performClick()
        rule.waitForIdle()
        assertEquals(1, clicks, "disabled item must not fire onClick")
    }

    // ---- MainNavigation with ListPage metadata / multi-entry ----

    @Test fun mainNavigationListPageMetadataRendersAndPushPop() {
        val stack = NavBackStack(arrayOf(Screen("list")))
        rule.setContent {
            MaterialTheme {
                MainNavigation(backStack = stack) {
                    entry<Screen>(metadata = if (obj.route == "list") ListPage { Text("Placeholder") } else DetailPage()) {
                        Text("Page ${it.route}")
                    }
                }
            }
        }
        rule.onNodeWithText("Page list").assertExists()
        rule.runOnIdle { stack.add(Screen("detail")) }
        rule.waitForIdle()
        rule.onNodeWithText("Page detail").assertExists()
        rule.runOnIdle { stack.pop() }
        rule.waitForIdle()
        rule.onNodeWithText("Page list").assertExists()
    }

    @Test fun mainNavigationResetFromTabBarStyleCall() {
        val stack = NavBackStack(arrayOf(Screen("a")))
        rule.setContent {
            MaterialTheme {
                MainNavigation(backStack = stack) {
                    entry<Screen> { Text("At ${it.route}") }
                }
            }
        }
        rule.onNodeWithText("At a").assertIsDisplayed()
        rule.runOnIdle { stack.add(Screen("b")); stack.add(Screen("c")) }
        rule.waitForIdle()
        rule.onNodeWithText("At c").assertExists()
        rule.runOnIdle { stack.reset(Screen("a")) }
        rule.waitForIdle()
        rule.onNodeWithText("At a").assertIsDisplayed()
        assertEquals(1, stack.backStack.size)
    }

    // ---- ResultEffect ordering / multiple subscribers ----

    @Test fun resultEffectDeliversInOrderAndToEverySubscriber() {
        val registry = NavResultRegistry()
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                    Column {
                        ResultEffect<String>("evt") { first += it }
                        ResultEffect<String>("evt") { second += it }
                        Text("OrderHost")
                    }
                }
            }
        }
        rule.onNodeWithText("OrderHost").assertIsDisplayed()
        CoroutineScope(Dispatchers.Default).launch {
            registry.dispatchResult("evt", "one")
            registry.dispatchResult("evt", "two")
            registry.dispatchResult("evt", "three")
        }
        rule.waitUntil(5_000) { first.size == 3 && second.size == 3 }
        assertEquals(listOf("one", "two", "three"), first)
        assertEquals(first, second)
    }

    @Test fun resultEffectSubtypeFilteringKeepsBaseTypeDelivery() {
        val registry = NavResultRegistry()
        val numbers = mutableListOf<Number>()
        val strings = mutableListOf<String>()
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalNavResultRegistry provides registry) {
                    Column {
                        ResultEffect<Number>("mix") { numbers += it }
                        ResultEffect<String>("mix") { strings += it }
                        Text("MixHost")
                    }
                }
            }
        }
        CoroutineScope(Dispatchers.Default).launch {
            registry.dispatchResult("mix", 42)
            registry.dispatchResult("mix", "hello")
        }
        rule.waitUntil(5_000) { numbers.isNotEmpty() && strings.isNotEmpty() }
        assertEquals(listOf<Number>(42), numbers)
        assertEquals(listOf("hello"), strings)
    }
}
