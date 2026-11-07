package com.vayunmathur.games.arrows

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityLaunchesAndShowsGame() {
        // The activity's Content creates the ViewModel and shows Navigation; the board's
        // fresh-board hint or the level title eventually appears.
        rule.waitUntil(timeoutMillis = 15_000) {
            try {
                rule.onNodeWithText("Tap an arrow", substring = true).assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }
        rule.onNodeWithText("Tap an arrow", substring = true).assertExists()
    }
}
