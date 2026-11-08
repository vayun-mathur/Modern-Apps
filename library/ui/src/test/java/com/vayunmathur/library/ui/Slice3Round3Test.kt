package com.vayunmathur.library.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice 3/3 round 3: remaining root composables — state screens, dashboard
 * sections, add-to-list dialog, search bar, overflow menu, visibilities.
 */
@RunWith(RobolectricTestRunner::class)
class Slice3Round3Test {
    @get:Rule val rule = createComposeRule()

    @Test fun emptyStateMinimalShowsTitleOnly() {
        rule.setContent { EmptyState(title = "Nothing here") }
        rule.onNodeWithText("Nothing here").assertIsDisplayed()
    }

    @Test fun emptyStateWithMessageIconAndAction() {
        var clicked = 0
        rule.setContent {
            EmptyState(
                title = "No items",
                message = "Try adding one",
                icon = { Text("empty-icon") },
                action = { TextButton(onClick = { clicked++ }) { Text("Add first") } },
            )
        }
        rule.onNodeWithText("No items").assertIsDisplayed()
        rule.onNodeWithText("Try adding one").assertIsDisplayed()
        rule.onNodeWithText("empty-icon").assertIsDisplayed()
        rule.onNodeWithText("Add first").performClick()
        assertEquals(1, clicked)
    }

    @Test fun loadingStateWithAndWithoutMessage() {
        rule.setContent { LoadingState(message = "Fetching") }
        rule.onNodeWithText("Fetching").assertIsDisplayed()
    }

    @Test fun loadingStateWithoutMessageRenders() {
        rule.setContent { LoadingState() }
        rule.waitForIdle()
    }

    @Test fun errorStateRetryFires() {
        var retries = 0
        rule.setContent {
            ErrorState(title = "Failed", message = "No network", retryLabel = "Retry", onRetry = { retries++ })
        }
        rule.onNodeWithText("Failed").assertIsDisplayed()
        rule.onNodeWithText("No network").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test fun errorStateWithoutRetryHasNoButton() {
        rule.setContent { ErrorState(title = "Broken") }
        rule.onNodeWithText("Broken").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Retry").fetchSemanticsNodes().isEmpty())
    }

    @Test fun sectionHeaderActionFires() {
        var acted = 0
        rule.setContent {
            SectionHeader(title = "Weather", actionLabel = "See all", onAction = { acted++ })
        }
        rule.onNodeWithText("Weather").assertIsDisplayed()
        rule.onNodeWithText("See all").performClick()
        assertEquals(1, acted)
    }

    @Test fun sectionHeaderWithoutActionHasNoButton() {
        rule.setContent { SectionHeader(title = "Plain") }
        rule.onNodeWithText("Plain").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("See all").fetchSemanticsNodes().isEmpty())
    }

    @Test fun dashboardSectionWithTitleAndContent() {
        var acted = 0
        rule.setContent {
            DashboardSection(title = "Cards", actionLabel = "More", onAction = { acted++ }) {
                Text("card-body")
            }
        }
        rule.onNodeWithText("Cards").assertIsDisplayed()
        rule.onNodeWithText("card-body").assertIsDisplayed()
        rule.onNodeWithText("More").performClick()
        assertEquals(1, acted)
    }

    @Test fun dashboardSectionWithoutTitleShowsContent() {
        rule.setContent { DashboardSection { Text("untitled-body") } }
        rule.onNodeWithText("untitled-body").assertIsDisplayed()
    }

    @Test fun dashboardDividerRenders() {
        rule.setContent { DashboardSectionDivider() }
        rule.waitForIdle()
    }

    @Test fun addToListConfirmReturnsCheckedSet() {
        var confirmed: Set<String>? = null
        rule.setContent {
            AddToListDialog(
                title = "Add to list",
                options = listOf("a", "b", "c"),
                itemLabel = { it },
                confirmLabel = "Done",
                dismissLabel = "Cancel",
                onConfirm = { confirmed = it },
                onDismiss = {},
                initiallyChecked = { it == "b" },
            )
        }
        rule.onNodeWithText("Add to list").assertIsDisplayed()
        // Toggle "a" on by clicking its row.
        rule.onNodeWithText("a").performClick()
        rule.onNodeWithText("Done").performClick()
        assertEquals(setOf("a", "b"), confirmed)
    }

    @Test fun addToListDismissDoesNotConfirm() {
        var confirmed: Set<String>? = null
        var dismissed = 0
        rule.setContent {
            AddToListDialog(
                title = "Pick",
                options = listOf("x"),
                itemLabel = { it },
                confirmLabel = "OK",
                dismissLabel = "Back",
                onConfirm = { confirmed = it },
                onDismiss = { dismissed++ },
            )
        }
        rule.onNodeWithText("Back").performClick()
        assertEquals(1, dismissed)
        assertEquals(null, confirmed)
    }

    @Test fun addToListCreateFlowCreatesAndClears() {
        val created = mutableListOf<String>()
        rule.setContent {
            AddToListDialog(
                title = "Pick",
                options = listOf("x"),
                itemLabel = { it },
                confirmLabel = "OK",
                dismissLabel = "Back",
                onConfirm = {},
                onDismiss = {},
                createLabel = "New list name",
                onCreate = { created += it },
            )
        }
        rule.onNodeWithText("New list name").performTextInput("  groceries ")
        rule.onNodeWithContentDescription("Add").performClick()
        assertEquals(listOf("groceries"), created)
    }

    @Test fun commonSearchBarShowsPlaceholderAndValue() {
        rule.setContent { CommonSearchBar(value = "", onValueChange = {}) }
        rule.onNodeWithText("Search").assertIsDisplayed()
    }

    @Test fun commonSearchBarClearButtonClearsValue() {
        var last: String? = null
        rule.setContent { CommonSearchBar(value = "query", onValueChange = { last = it }) }
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals("", last)
    }

    @Test fun overflowMenuOpensAndItemFiresAndCloses() {
        var picked = 0
        rule.setContent {
            OverflowMenu {
                Item(text = "Settings") { picked++ }
                Item(text = "Disabled", enabled = false) { picked += 10 }
            }
        }
        rule.onNodeWithContentDescription("Menu").performClick()
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithText("Disabled").assertIsNotEnabled()
        rule.onNodeWithText("Settings").performClick()
        assertEquals(1, picked)
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Settings").fetchSemanticsNodes().isEmpty())
    }

    @Test fun bannerVisibilityVisibleShowsContent() {
        rule.setContent { BannerVisibility(visible = true) { Text("banner-text") } }
        rule.onNodeWithText("banner-text").assertIsDisplayed()
    }

    @Test fun expandVisibilityVisibleShowsContent() {
        rule.setContent { ExpandVisibility(visible = true) { Text("expand-text") } }
        rule.onNodeWithText("expand-text").assertIsDisplayed()
    }

    @Test fun fadeVisibilityVisibleShowsContent() {
        rule.setContent { FadeVisibility(visible = true) { Text("fade-text") } }
        rule.onNodeWithText("fade-text").assertIsDisplayed()
    }

    @Test fun popVisibilityVisibleShowsContent() {
        rule.setContent { PopVisibility(visible = true) { Text("pop-text") } }
        rule.onNodeWithText("pop-text").assertIsDisplayed()
    }
}
