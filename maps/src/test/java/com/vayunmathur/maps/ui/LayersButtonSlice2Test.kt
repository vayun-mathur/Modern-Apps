package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The map-layers entry-point button.
 * Slice-2 coverage for maps (basename L, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class LayersButtonSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the button shows`() {
        rule.setContent { LayersButton(onClick = {}) }
        rule.onNodeWithContentDescription("Style").assertIsDisplayed()
    }

    @Test
    fun `tapping it opens the sheet`() {
        var clicks = 0
        rule.setContent { LayersButton(onClick = { clicks++ }) }
        rule.onNodeWithContentDescription("Style").performClick()
        assertEquals(1, clicks)
    }
}
