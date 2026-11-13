package com.vayunmathur.games.voxels.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w740dp-h360dp-land")
class ChestOverlayTest {
    @get:Rule val rule = createComposeRule()

    // On a short landscape screen the hotbar row (the last one) used to be clipped off the panel.
    @Test fun `the hotbar row of a chest screen is reachable in landscape`() {
        rule.setContent {
            ChestOverlay(
                containerJson = """{"slots":[{"id":8,"count":3}]}""",
                inventoryJson = """{"selected":0,"slots":[{"id":1,"count":47}]}""",
                onClose = {},
            )
        }
        rule.onNodeWithText("3").assertIsDisplayed()
        rule.onNodeWithText("47").performScrollTo().assertIsDisplayed()
    }
}
