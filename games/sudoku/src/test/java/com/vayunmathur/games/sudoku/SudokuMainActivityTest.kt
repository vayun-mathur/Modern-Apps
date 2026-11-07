package com.vayunmathur.games.sudoku

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
class SudokuMainActivityTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun mainActivityLaunchesToHome() {
        rule.onNodeWithText("New Game").assertExists()
        rule.onNodeWithText("Board size").assertExists()
    }
}
