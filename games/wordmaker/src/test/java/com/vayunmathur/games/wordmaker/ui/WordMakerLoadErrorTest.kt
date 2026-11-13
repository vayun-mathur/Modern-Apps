package com.vayunmathur.games.wordmaker.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.library.ui.DynamicTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class WordMakerLoadErrorTest {
    @get:Rule val rule = createComposeRule()

    @Test fun parseErrorOffersRetryInsideTheGameScaffold() {
        var retries = 0
        rule.setContent {
            DynamicTheme {
                WordMakerLoadError(
                    message = "Failed to parse level data.",
                    gameMode = GameMode.CASUAL,
                    levelNumber = 7,
                    onSelectMode = {},
                    onRetry = { retries++ },
                    onOpenGameCenter = {},
                    onOpenSettings = {},
                )
            }
        }
        rule.onNodeWithText("Failed to parse level data.").assertIsDisplayed()
        rule.onNodeWithText("Retry").assertIsDisplayed()
        rule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }
}
