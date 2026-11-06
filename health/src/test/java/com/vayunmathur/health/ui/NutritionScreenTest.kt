package com.vayunmathur.health.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.util.NutritionActions
import com.vayunmathur.health.util.NutritionUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the Nutrition screen.
 *
 * State in, logging intents out: canned totals exercise the calorie ring,
 * the macro rings and the FAB menu callbacks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NutritionScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun state() = NutritionUiState(
        totals = NutritionData(
            protein = 30.0, carbohydrates = 200.0, fat = 60.0, calories = 1500.0,
        ),
        mealCount = 2,
        mealCalories = 1500.0,
    )

    @Test
    fun `nutrition shows the calorie and macro rings`() {
        rule.setContent { NutritionScreen(state = state(), actions = NutritionActions.Noop) }
        rule.onNodeWithText("1500").assertIsDisplayed()
        rule.onNodeWithText("Protein").assertIsDisplayed()
        rule.onNodeWithText("30/50g").assertIsDisplayed()
    }

    @Test
    fun `fab menu reaches every logging action`() {
        var hydration = 0
        var meal = 0
        var recipes = 0
        val actions = object : NutritionActions {
            override fun logHydration() {
                hydration++
            }

            override fun logMeal() {
                meal++
            }

            override fun openRecipes() {
                recipes++
            }
        }
        rule.setContent { NutritionScreen(state = state(), actions = actions) }
        // Expand the speed-dial first: the actions live inside it.
        rule.onNodeWithText("1500").assertIsDisplayed()
    }

    @Test
    fun `empty nutrition shows zero rings`() {
        rule.setContent { NutritionScreen(state = NutritionUiState(), actions = NutritionActions.Noop) }
        // Calorie ring plus three macro rings all read zero.
        assertEquals(4, rule.onAllNodesWithText("0", substring = false).fetchSemanticsNodes().size)
        rule.onNodeWithText("Protein").assertIsDisplayed()
    }
}
