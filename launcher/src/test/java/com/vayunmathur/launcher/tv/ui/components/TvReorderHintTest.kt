package com.vayunmathur.launcher.tv.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Compose tests (Robolectric + createComposeRule) for [TvReorderHint]:
 * the hint names the keys for moving vs armed states, and is empty
 * otherwise.
 */
@RunWith(AndroidJUnit4::class)
class TvReorderHintTest {

    @get:Rule val rule = createComposeRule()

    @Test fun movingHintNamesArrowsOkBack() {
        rule.setContent { TvReorderHint(moving = true, armed = true) }
        rule.onNodeWithText("Arrows move • OK drops • Back cancels").assertIsDisplayed()
    }

    @Test fun armedHintNamesOkBack() {
        rule.setContent { TvReorderHint(moving = false, armed = true) }
        rule.onNodeWithText("OK picks up • Back done").assertIsDisplayed()
    }

    @Test fun idleShowsNoHint() {
        rule.setContent { TvReorderHint(moving = false, armed = false) }
        assertEquals(0, rule.onAllNodesWithText("OK").fetchSemanticsNodes().size)
    }
}
