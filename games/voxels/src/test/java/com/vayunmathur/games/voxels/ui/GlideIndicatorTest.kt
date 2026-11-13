package com.vayunmathur.games.voxels.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
class GlideIndicatorTest {
    @get:Rule val rule = createComposeRule()

    // The hint must name the button players actually see.
    @Test fun `the elytra hint names the Jump button`() {
        rule.setContent { GlideIndicator(healthJson = """{"elytra":true,"gliding":false}""") }
        rule.onNodeWithText("Tap Jump in mid-air to glide").assertExists()
    }
}
