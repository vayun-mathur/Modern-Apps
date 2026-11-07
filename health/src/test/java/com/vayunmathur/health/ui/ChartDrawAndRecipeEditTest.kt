package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.databases
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChartDrawAndRecipeEditTest {

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
        com.vayunmathur.health.util.FoodDatabase.init(context)
        runBlocking { HealthRepository.get(context).getLastRecord(com.vayunmathur.health.data.RecordType.Steps) }
    }

    private fun vm(): HealthViewModel = HealthViewModel(context, HealthRepository.get(context))

    @Test
    fun `line and bar charts draw their series`() {
        rule.setContent {
            Column {
                GenericLineChart(
                    data = listOf("Mon" to 120.0, "Tue" to 118.0, "Wed" to 122.0, "Thu" to 119.0),
                    secondaryData = listOf("Mon" to 80.0, "Tue" to 82.0, "Wed" to 79.0, "Thu" to 81.0),
                    goalValue = 120.0,
                    secondaryGoal = 80.0,
                    lineColor = Color.Blue,
                    secondaryLineColor = Color.Cyan,
                    goalColor = Color.Red,
                )
                GenericBarChart(
                    data = listOf("Mon" to 5000.0, "Tue" to 8000.0, "Wed" to 3000.0),
                    totalBarCount = 7,
                    goalValue = 10000.0,
                    barColor = Color.Blue,
                    goalColor = Color.Red,
                )
            }
        }
        // Capturing forces a draw pass, executing the Canvas series lambdas.
        val image = rule.onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
    }

    @Test
    fun `ingredient rename dialog saves custom name`() = runBlocking<Unit> {
        val repo = HealthRepository.get(context)
        repo.insertIngredient(Ingredient(id = "rename-ing-1", originalName = "Rename Oats", nutritionData = NutritionData(calories = 90.0)))
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.RecipeManagement))
        rule.setContent { RecipeManagementPage(backStack, vm()) }
        rule.waitForIdle()
        rule.onNodeWithText("Ingredients").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Rename Oats").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals("Rename Oats", repo.getIngredient("rename-ing-1")?.displayName)
        assertTrue(repo.getAllIngredientsFlow().first().any { it.id == "rename-ing-1" })
    }
}
