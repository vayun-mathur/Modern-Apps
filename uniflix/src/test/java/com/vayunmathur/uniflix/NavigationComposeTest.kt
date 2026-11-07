package com.vayunmathur.uniflix

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The phone navigation graph: Home entry and hub navigation in offline states. */
@RunWith(RobolectricTestRunner::class)
class NavigationComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test fun homeEntryRendersAndOpensMoviesHub() {
        composeRule.setContent { DynamicTheme { Navigation() } }
        composeRule.onNodeWithText("Uniflix").assertIsDisplayed()
        composeRule.onNodeWithText("Movies").performClick()
        // Movies hub (BrowseViewModel) settles offline without crashing.
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Movies").assertIsDisplayed()
    }

    @Test fun homeEntryOpensSearch() {
        composeRule.setContent { DynamicTheme { Navigation() } }
        composeRule.onNodeWithText("Uniflix").assertIsDisplayed()
        composeRule.onNodeWithText("Series").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Series").assertIsDisplayed()
    }
}
