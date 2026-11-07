package com.vayunmathur.code.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.code.Route
import com.vayunmathur.code.syntax.Language
import com.vayunmathur.code.util.EditorViewModel
import com.vayunmathur.code.util.OpenTab
import com.vayunmathur.code.util.SearchResult
import com.vayunmathur.library.util.NavBackStack
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertTrue

/** Robolectric Compose tests for [SearchPage] and [PreviewPage]. */
@RunWith(AndroidJUnit4::class)
class SearchPreviewUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun vm() = EditorViewModel(ApplicationProvider.getApplicationContext())

    private fun backStack() = NavBackStack(arrayOf<Route>(Route.Editor))

    @Test
    fun searchPageShowsFieldAndToggles() {
        rule.setContent { SearchPage(vm(), backStack()) }
        rule.onNodeWithText("Search in project").assertIsDisplayed()
        rule.onNodeWithText("Match case").assertIsDisplayed()
        rule.onNodeWithText("Regex").assertIsDisplayed()
    }

    @Test
    fun searchPageToggleChipsFlip() {
        rule.setContent { SearchPage(vm(), backStack()) }
        rule.onNodeWithText("Match case").performClick()
        rule.onNodeWithText("Regex").performClick()
        // Still on the page; chips toggled without crashing.
        rule.onNodeWithText("Search in project").assertIsDisplayed()
    }

    @Test
    fun searchPageShowsResultsGrouped() {
        val v = vm()
        v.searchResults.add(SearchResult("/p/a.kt", "a.kt", 3, "fun target()"))
        v.searchResults.add(SearchResult("/p/a.kt", "a.kt", 7, "target again"))
        rule.setContent { SearchPage(v, backStack()) }
        rule.onNodeWithText("a.kt").assertIsDisplayed()
        rule.onNodeWithText("fun target()", substring = true).assertIsDisplayed()
        rule.onNodeWithText("3", substring = false).assertIsDisplayed()
    }

    @Test
    fun searchPageResultClickOpensAndPops() {
        val v = vm()
        v.searchResults.add(SearchResult("/p/a.kt", "a.kt", 3, "fun target()"))
        val stack = NavBackStack(arrayOf(Route.Editor, Route.Search))
        rule.setContent { SearchPage(v, stack) }
        rule.onNodeWithText("fun target()", substring = true).performClick()
        // Popped back to the editor.
        assertTrue(stack.backStack.size == 1)
    }

    @Test
    fun searchPageSearchingShowsProgress() {
        val v = vm()
        v.isSearching = true
        rule.setContent { SearchPage(v, backStack()) }
        // Progress indicator renders (no text); page chrome still present.
        rule.onNodeWithText("Search in project").assertIsDisplayed()
    }

    @Test
    fun previewUnsupportedShowsLabel() {
        val v = vm()
        v.tabs.add(
            OpenTab(null, initialName = "a.kt", initialText = "fun f() {}", language = Language.KOTLIN),
        )
        v.currentIndex = 0
        rule.setContent { PreviewPage(v, backStack()) }
        rule.onNodeWithText("No preview for", substring = true).assertIsDisplayed()
    }
}
