package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The heading-up / north-up toggle.
 * Slice-2 coverage for maps (basename C, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class CompassButtonSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `at north-up the button hides itself`() {
        rule.setContent { CompassButton(bearing = 0.0, onResetNorth = {}) }
        rule.onNodeWithContentDescription("Compass").assertDoesNotExist()
    }

    @Test
    fun `a sub-degree bearing still counts as north-up`() {
        rule.setContent { CompassButton(bearing = 0.9, onResetNorth = {}) }
        rule.onNodeWithContentDescription("Compass").assertDoesNotExist()
    }

    @Test
    fun `a rotated map shows the compass`() {
        var resets = 0
        rule.setContent { CompassButton(bearing = 45.0, onResetNorth = { resets++ }) }
        rule.onNodeWithContentDescription("Compass").assertIsDisplayed()
        rule.onNodeWithContentDescription("Compass").performClick()
        assertEquals(1, resets)
    }

    @Test
    fun `a 359-degree bearing reads as minus one and shows the compass`() {
        rule.setContent { CompassButton(bearing = 359.0, onResetNorth = {}) }
        rule.onNodeWithContentDescription("Compass").assertIsDisplayed()
    }

    @Test
    fun `a negative bearing shows the compass`() {
        rule.setContent { CompassButton(bearing = -45.0, onResetNorth = {}) }
        rule.onNodeWithContentDescription("Compass").assertIsDisplayed()
    }
}
