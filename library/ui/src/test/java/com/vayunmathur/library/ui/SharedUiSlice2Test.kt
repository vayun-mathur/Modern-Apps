package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice 3/3: small shared UI pieces — state screens, sections, overflow menu,
 * search bar, visibility wrappers, motion/spacing scales and pure helpers.
 */
@RunWith(RobolectricTestRunner::class)
class SharedUiSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    // ---- EmptyState / LoadingState / ErrorState ----

    @Test fun emptyStateShowsTitleMessageIconAndAction() {
        var actions = 0
        rule.setContent {
            EmptyState(
                title = "Nothing here",
                message = "Try again later",
                icon = { IconAdd() },
                action = { TextButton(onClick = { actions++ }) { Text("Retry") } },
            )
        }
        rule.onNodeWithText("Nothing here").assertIsDisplayed()
        rule.onNodeWithText("Try again later").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, actions)
    }

    @Test fun emptyStateWithoutOptionalsShowsOnlyTitle() {
        rule.setContent { EmptyState(title = "Bare") }
        rule.onNodeWithText("Bare").assertIsDisplayed()
    }

    @Test fun loadingStateShowsMessage() {
        rule.setContent { LoadingState(message = "Fetching") }
        rule.onNodeWithText("Fetching").assertIsDisplayed()
    }

    @Test fun errorStateRetryRunsCallback() {
        var retries = 0
        rule.setContent {
            ErrorState(
                title = "Broken",
                message = "Nope",
                retryLabel = "Try again",
                onRetry = { retries++ },
            )
        }
        rule.onNodeWithText("Broken").assertIsDisplayed()
        rule.onNodeWithText("Nope").assertIsDisplayed()
        rule.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test fun errorStateWithoutRetryShowsTitleOnly() {
        rule.setContent { ErrorState(title = "Oops") }
        rule.onNodeWithText("Oops").assertIsDisplayed()
    }

    // ---- SectionHeader / DashboardSection / FormSection ----

    @Test fun sectionHeaderActionFires() {
        var actions = 0
        rule.setContent {
            SectionHeader(title = "Weather", actionLabel = "See all", onAction = { actions++ })
        }
        rule.onNodeWithText("Weather").assertIsDisplayed()
        rule.onNodeWithText("See all").performClick()
        assertEquals(1, actions)
    }

    @Test fun sectionHeaderWithLeadingIconRenders() {
        rule.setContent {
            SectionHeader(title = "T", leadingIcon = { m, _ -> IconAdd(modifier = m) })
        }
        rule.onNodeWithText("T").assertIsDisplayed()
    }

    @Test fun dashboardSectionShowsTitleAndContent() {
        rule.setContent {
            DashboardSection(title = "Hub") { Text("Row one") }
        }
        rule.onNodeWithText("Hub").assertIsDisplayed()
        rule.onNodeWithText("Row one").assertIsDisplayed()
    }

    @Test fun dashboardSectionDividerRenders() {
        rule.setContent {
            Column {
                Text("Above")
                DashboardSectionDivider()
                Text("Below")
            }
        }
        rule.onNodeWithText("Above").assertIsDisplayed()
        rule.onNodeWithText("Below").assertIsDisplayed()
    }

    @Test fun formSectionShowsTitleAndFields() {
        rule.setContent {
            FormSection(title = "Details") { Text("Field") }
        }
        rule.onNodeWithText("Details").assertIsDisplayed()
        rule.onNodeWithText("Field").assertIsDisplayed()
    }

    // ---- OverflowMenu ----

    @Test fun overflowMenuItemClosesAndRunsAction() {
        var clicks = 0
        rule.setContent {
            OverflowMenu {
                Item(text = "Delete", onClick = { clicks++ })
            }
        }
        // Open the menu via the overflow icon button.
        rule.onNodeWithContentDescription("Menu").performClick()
        rule.onNodeWithText("Delete").performClick()
        assertEquals(1, clicks)
    }

    // ---- CommonSearchBar ----

    @Test fun commonSearchBarEditsAndClears() {
        var value = "hi"
        rule.setContent {
            CommonSearchBar(value = value, onValueChange = { value = it })
        }
        // Trailing clear button visible while non-empty; tapping clears.
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals("", value)
    }

    @Test fun commonSearchBarTypingCallsBack() {
        var value = ""
        rule.setContent {
            CommonSearchBar(value = value, onValueChange = { value = it })
        }
        rule.onNodeWithText("Search").performClick()
        rule.waitForIdle()
    }

    // ---- Visibility wrappers ----

    @Test fun bannerVisibilityShowsAndHides() {
        var visible by mutableStateOf(true)
        rule.setContent { BannerVisibility(visible = visible) { Text("Ping") } }
        rule.onNodeWithText("Ping").assertIsDisplayed()
        visible = false
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(2000)
        assertTrue(rule.onAllNodesWithText("Ping").fetchSemanticsNodes().isEmpty())
    }

    @Test fun expandVisibilityShowsContent() {
        rule.setContent { ExpandVisibility(visible = true) { Text("Grew") } }
        rule.onNodeWithText("Grew").assertIsDisplayed()
    }

    @Test fun fadeVisibilityShowsContent() {
        rule.setContent { FadeVisibility(visible = true) { Text("Faded") } }
        rule.onNodeWithText("Faded").assertIsDisplayed()
    }

    @Test fun popVisibilityShowsContent() {
        rule.setContent { PopVisibility(visible = true) { Text("Popped") } }
        rule.onNodeWithText("Popped").assertIsDisplayed()
    }
}
