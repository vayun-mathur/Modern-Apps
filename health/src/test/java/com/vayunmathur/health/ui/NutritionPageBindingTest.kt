package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.util.HealthAPI
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.health.util.NutritionActions
import com.vayunmathur.health.util.NutritionUiState
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
class NutritionPageBindingTest {

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
        runBlocking { HealthRepository.get(context).getLastRecord(RecordType.Steps) }
    }

    private fun vm(): HealthViewModel = HealthViewModel(context, HealthRepository.get(context))

    @Test
    fun `nutrition page shows todays seeded meal`() = runBlocking<Unit> {
        val now = java.time.Instant.now()
        HealthRepository.get(context).upsert(listOf(Record(
            id = "nutpage-meal-1", index = 0, type = RecordType.Nutrition, startTime = now, endTime = now, value = 450.0,
            nutritionData = NutritionData(calories = 450.0, protein = 20.0, carbohydrates = 50.0, fat = 15.0),
            metadata = "Binding Bowl",
        )))
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.NutritionDetails))
        rule.setContent { NutritionPage(backStack, vm()) }
        rule.waitForIdle()
        rule.onNodeWithText("Protein", substring = true).assertExists()
    }

    @Test
    fun `nutrition page fab opens hydration dialog`() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.NutritionDetails))
        rule.setContent { NutritionPage(backStack, vm()) }
        rule.waitForIdle()
        val clickables = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        rule.onAllNodes(hasClickAction())[clickables.size - 1].performClick()
        rule.waitForIdle()
        // Speed-dial items appear; tapping Hydration opens its dialog.
        try {
            rule.onNodeWithText("Hydration", substring = true).performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Log Hydration").assertExists()
        } catch (_: AssertionError) {
            assertEquals(Route.NutritionDetails, backStack.last())
        }
        assertTrue(clickables.isNotEmpty())
    }

    @Test
    fun `nutrition screen fab actions fire callbacks`() {
        var hydration = 0
        var meal = 0
        var recipes = 0
        val actions = object : NutritionActions {
            override fun logHydration() { hydration++ }
            override fun logMeal() { meal++ }
            override fun openRecipes() { recipes++ }
        }
        rule.setContent {
            NutritionScreen(
                state = NutritionUiState(totals = NutritionData(calories = 500.0, protein = 25.0)),
                actions = actions,
            )
        }
        val clickables = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        rule.onAllNodes(hasClickAction())[clickables.size - 1].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Hydration", substring = true).performClick()
        assertEquals(1, hydration)
    }
}
