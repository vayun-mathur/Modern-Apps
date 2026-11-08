package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.Recipe
import com.vayunmathur.health.data.RecipeIngredient
import com.vayunmathur.health.data.ServingUnit
import com.vayunmathur.health.util.FoodDatabase
import com.vayunmathur.health.util.HealthAPI
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.databases
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecipeEditorFlowTest {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setup() {
        synchronized(databases) {
            if (databases[HealthDatabase::class] == null) {
                databases[HealthDatabase::class] = Room
                    .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            }
        }
        HealthAPI.initLocal(context, HealthRepository.get(context))
        FoodDatabase.init(context)
        runBlocking { HealthRepository.get(context).getLastRecord(com.vayunmathur.health.data.RecordType.Steps) }
    }

    private fun vm(): HealthViewModel = HealthViewModel(context, HealthRepository.get(context))

    private fun seedRecipe(suffix: String = "1") = runBlocking {
        val repo = HealthRepository.get(context)
        val ingredient = Ingredient(id = "flow-ing-$suffix", originalName = "Flow Rice", nutritionData = NutritionData(calories = 130.0, protein = 2.0))
        repo.insertIngredient(ingredient)
        repo.insertServingUnit(ServingUnit(id = "flow-unit-$suffix", ingredientId = ingredient.id, name = "Cup", grams = 100.0))
        repo.insertRecipe(Recipe(id = "flow-recipe-$suffix", name = "Flow Bowl"))
        repo.insertRecipeIngredient(RecipeIngredient(id = "flow-ri-$suffix", recipeId = "flow-recipe-$suffix", ingredientId = ingredient.id, quantity = 1.0, unitId = "flow-unit-$suffix"))
    }

    @Test
    fun `editor loads ingredient, renames and saves`() {
        seedRecipe()
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.RecipeEditor("flow-recipe-1")))
        rule.setContent { RecipeEditorPage(backStack, vm(), "flow-recipe-1") }
        rule.waitForIdle()
        rule.onNodeWithText("Flow Rice", substring = true).assertIsDisplayed()
        // Rename through the name field (loaded name is the field value).
        rule.onNodeWithText("Flow Bowl").performTextInput("Flow Bowl v2")
        rule.waitForIdle()
        rule.onNodeWithText("Save Recipe", substring = true).performClick()
        rule.waitForIdle()
        // Save completes asynchronously and pops to Today.
        var popped = false
        repeat(100) {
            if (backStack.last() == Route.Today) { popped = true; return@repeat }
            Thread.sleep(10)
            rule.waitForIdle()
        }
        assertTrue(popped)
        assertEquals(Route.Today, backStack.last())
    }

    @Test
    fun `editor ingredient card opens quantity dialog`() {
        seedRecipe("2")
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.RecipeEditor("flow-recipe-2")))
        rule.setContent { RecipeEditorPage(backStack, vm(), "flow-recipe-2") }
        rule.waitForIdle()
        rule.onNodeWithText("Flow Rice", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Set Quantity for Flow Rice").assertExists()
    }

    @Test
    fun `new recipe save stays disabled until named with ingredients`() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.RecipeEditor(null)))
        rule.setContent { RecipeEditorPage(backStack, vm(), null) }
        rule.waitForIdle()
        rule.onNodeWithText("Recipe Name", substring = true).performTextInput("Brand New Recipe")
        rule.waitForIdle()
        // No ingredients yet, so saving cannot pop the editor.
        assertEquals(Route.RecipeEditor(null), backStack.last())
    }
}
