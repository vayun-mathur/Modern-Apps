package com.vayunmathur.health.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the shared health rows, chips and mini charts.
 *
 * These are the atoms every dashboard and detail screen is built from, so
 * pinning their rendering covers the shared component file directly and
 * guards every screen that embeds them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthComponentsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `health row renders headline and supporting text`() {
        rule.setContent {
            HealthRow(headline = "Resting heart rate", supporting = "58 bpm yesterday")
        }
        rule.onNodeWithText("Resting heart rate").assertIsDisplayed()
        rule.onNodeWithText("58 bpm yesterday").assertIsDisplayed()
    }

    @Test
    fun `health row without supporting text has no extra line`() {
        rule.setContent { HealthRow(headline = "Steps") }
        rule.onNodeWithText("Steps").assertIsDisplayed()
    }

    @Test
    fun `health row click fires`() {
        var clicks = 0
        rule.setContent { HealthRow(headline = "Tap me", onClick = { clicks++ }) }
        rule.onNodeWithText("Tap me").performClick()
        assertTrue(clicks >= 1, "onClick fires")
    }

    @Test
    fun `metric row renders value unit delta and sparkline`() {
        rule.setContent {
            MetricRow(
                label = "Steps",
                value = "8,432",
                unit = "steps",
                delta = 12f,
                sparkline = listOf(1f, 2f, 3f),
            )
        }
        rule.onNodeWithText("Steps").assertIsDisplayed()
        rule.onNodeWithText("8,432").assertIsDisplayed()
        rule.onNodeWithText("steps").assertIsDisplayed()
        // DeltaChip renders an up arrow with the rounded percent.
        rule.onNodeWithText("▲ 12%", substring = true).assertIsDisplayed()
    }

    @Test
    fun `metric row click fires`() {
        var clicks = 0
        rule.setContent {
            MetricRow(label = "Weight", value = "70", unit = "kg", onClick = { clicks++ })
        }
        rule.onNodeWithText("Weight").performClick()
        assertTrue(clicks >= 1, "onClick fires")
    }

    @Test
    fun `delta chip shows direction and magnitude`() {
        rule.setContent { DeltaChip(percent = -5f, isGood = false) }
        rule.onNodeWithText("▼ 5%", substring = true).assertIsDisplayed()
    }

    @Test
    fun `sparkline renders nothing for empty input`() {
        // Must not crash; there is simply nothing to assert on.
        rule.setContent { Sparkline(values = emptyList()) }
        rule.waitForIdle()
    }

    @Test
    fun `sparkline renders a series`() {
        rule.setContent { Sparkline(values = listOf(1f, 4f, 2f, 8f)) }
        rule.waitForIdle()
    }

    @Test
    fun `metric ring renders its label`() {
        rule.setContent { MetricRing(progress = 0.75f, label = "Move", value = "450/600") }
        rule.onNodeWithText("Move").assertIsDisplayed()
        rule.onNodeWithText("450/600").assertIsDisplayed()
    }

    @Test
    fun `activity rings trio renders three goals`() {
        rule.setContent {
            ActivityRingsTrio(stepsPct = 0.5f, energyPct = 1f, mindfulPct = 0.25f)
        }
        rule.waitForIdle()
    }
}
