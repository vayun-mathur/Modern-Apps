package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The quick-category chip row and its shared definitions.
 * Slice-2 coverage for maps (basename C, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class CategoryChipsSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `every category renders its label`() {
        rule.setContent { CategoryChips(onCategory = {}) }
        for (label in listOf("Restaurants", "Coffee", "Gas", "Groceries", "Hotels", "ATMs")) {
            rule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun `six categories are shared by browse and search`() {
        assertEquals(6, MAP_CATEGORIES.size)
        // Queries run the Google search; types filter the offline index; kinds
        // filter the drawn renderer layers — none may be empty.
        for (category in MAP_CATEGORIES) {
            assert(category.query.isNotBlank())
            assert(category.types.isNotEmpty())
            assert(category.kinds.isNotEmpty())
        }
    }

    @Test
    fun `tapping a chip reports its category`() {
        var picked: MapCategory? = null
        rule.setContent { CategoryChips(onCategory = { picked = it }) }
        rule.onNodeWithText("Coffee").performClick()
        assertEquals(MAP_CATEGORIES[1], picked)
        assertEquals("coffee", picked?.query)
    }

    @Test
    fun `the selected chip reads selected`() {
        rule.setContent { CategoryChips(onCategory = {}, selected = MAP_CATEGORIES[0]) }
        rule.onNodeWithText("Restaurants").assertIsSelected()
    }

    @Test
    fun `the gas chip filters fuel kinds and the gas type`() {
        var picked: MapCategory? = null
        rule.setContent { CategoryChips(onCategory = { picked = it }) }
        rule.onNodeWithText("Gas").performClick()
        assertEquals(MAP_CATEGORIES[2], picked)
        assertEquals(setOf(6), picked?.types)
        assertEquals(setOf("fuel"), picked?.kinds)
    }
}
