package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.MapBody
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The Earth/Moon body switch.
 * Slice-2 coverage for maps (basename B, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class BodyDropdownSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the chip reads the current body`() {
        rule.setContent { BodyDropdown(selected = MapBody.Earth, onSelect = {}) }
        rule.onNodeWithText("Earth").assertIsDisplayed()
    }

    @Test
    fun `opening the menu offers both bodies`() {
        rule.setContent { BodyDropdown(selected = MapBody.Earth, onSelect = {}) }
        rule.onNodeWithText("Earth").performClick()
        rule.onNodeWithText("Moon").assertIsDisplayed()
    }

    @Test
    fun `selecting moon reports moon`() {
        var selected: MapBody? = null
        rule.setContent { BodyDropdown(selected = MapBody.Earth, onSelect = { selected = it }) }
        rule.onNodeWithText("Earth").performClick()
        rule.onNodeWithText("Moon").performClick()
        assertEquals(MapBody.Moon, selected)
    }

    @Test
    fun `when moon is current the chip reads moon`() {
        rule.setContent { BodyDropdown(selected = MapBody.Moon, onSelect = {}) }
        rule.onNodeWithText("Moon").assertIsDisplayed()
    }
}
