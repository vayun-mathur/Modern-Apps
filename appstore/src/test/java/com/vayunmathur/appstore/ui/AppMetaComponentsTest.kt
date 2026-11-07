package com.vayunmathur.appstore.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Small read-only detail-page pieces: the facts-grid cell and the
 * category / anti-feature chip. Rendered under Robolectric with the
 * Compose test rule (Espresso idle-sync is broken on-device).
 */
@RunWith(RobolectricTestRunner::class)
class AppMetaComponentsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `stat cell shows the value above the label`() {
        rule.setContent { StatCell(label = "Version", value = "1.2.3") }

        rule.onNodeWithText("1.2.3").assertIsDisplayed()
        rule.onNodeWithText("Version").assertIsDisplayed()
    }

    @Test
    fun `info chip shows its text in both modes`() {
        rule.setContent { InfoChip(text = "Games") }
        rule.onNodeWithText("Games").assertIsDisplayed()
    }

    @Test
    fun `emphasised chip shows its text`() {
        rule.setContent { InfoChip(text = "Tracks you", emphasise = true) }
        rule.onNodeWithText("Tracks you").assertIsDisplayed()
    }
}
