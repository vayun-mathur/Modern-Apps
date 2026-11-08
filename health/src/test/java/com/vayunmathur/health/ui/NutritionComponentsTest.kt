package com.vayunmathur.health.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vayunmathur.health.data.NutritionData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the nutrition atoms: the macro ring, the nutrient progress row,
 * and the shared daily-value catalog behind both nutrition screens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NutritionComponentsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `macro ring shows value over goal`() {
        rule.setContent {
            CompactMacroRing(
                label = "Protein",
                value = 30.0,
                goal = 50.0,
                unit = "g",
                color = Color.Red,
            )
        }
        rule.onNodeWithText("Protein").assertIsDisplayed()
        rule.onNodeWithText("30").assertIsDisplayed()
        rule.onNodeWithText("30/50g").assertIsDisplayed()
    }

    @Test
    fun `nutrient row shows amount over daily value`() {
        val protein = nutrientCatalog.first { it.name == "Protein" }
        rule.setContent {
            NutrientProgressRow(nutrient = protein, currentAmount = 25.0)
        }
        rule.onNodeWithText("Protein").assertIsDisplayed()
        rule.onNodeWithText("25.0 / 50g").assertIsDisplayed()
    }

    @Test
    fun `every catalog entry reads its own nutrient`() {
        val totals = NutritionData(
            protein = 1.0, carbohydrates = 2.0, fat = 3.0, fiber = 4.0, sugar = 5.0,
            sodium = 6.0, cholesterol = 7.0, vitaminC = 8.0, calcium = 9.0, iron = 10.0,
            potassium = 11.0, caffeine = 12.0,
        )
        val byName = nutrientCatalog.associate { it.name to it.accessor(totals) }
        assertEquals(1.0, byName["Protein"])
        assertEquals(2.0, byName["Carbohydrates"])
        assertEquals(3.0, byName["Fat"])
        assertEquals(4.0, byName["Fiber"])
        assertEquals(5.0, byName["Sugar"])
        assertEquals(6.0, byName["Sodium"])
        assertEquals(7.0, byName["Cholesterol"])
        assertEquals(8.0, byName["Vitamin C"])
        assertEquals(9.0, byName["Calcium"])
        assertEquals(10.0, byName["Iron"])
        assertEquals(11.0, byName["Potassium"])
        assertEquals(12.0, byName["Caffeine"])
    }

    @Test
    fun `catalog entries are distinct and positive`() {
        val names = nutrientCatalog.map { it.name }
        assertEquals(names.size, names.distinct().size, "a nutrient is listed twice")
        for (entry in nutrientCatalog) {
            assertTrue(entry.dailyValue > 0, "${entry.name} has no daily value")
            assertTrue(entry.unit.isNotBlank(), "${entry.name} has no unit")
        }
    }

    @Test
    fun `hydration stands apart from the summed nutrients`() {
        assertEquals("Hydration", hydrationNutrient.name)
        assertEquals(0.0, hydrationNutrient.accessor(NutritionData(protein = 99.0)))
    }
}
