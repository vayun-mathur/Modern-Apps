package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.Recipe
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.util.FoodDatabase
import com.vayunmathur.health.util.HealthAPI
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.health.util.MainPageMetrics
import com.vayunmathur.health.util.TodayActions
import com.vayunmathur.health.util.TodayUiState
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
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
class InteractionsTest {

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
        com.vayunmathur.health.data.ReferenceCatalog.init(context)
        runBlocking { HealthRepository.get(context).getLastRecord(com.vayunmathur.health.data.RecordType.Steps) }
    }

    private fun vm(): HealthViewModel = HealthViewModel(context, HealthRepository.get(context))
    private fun medicalVm(): MedicalViewModel = MedicalViewModel(context, HealthRepository.get(context))

    @Test
    fun `catalog vaccine list click sets draft and pops`() {
        val viewModel = medicalVm()
        viewModel.startVaccinationDraft()
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.EditVaccination(), Route.CatalogPicker(CatalogKind.Vaccine)))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                CatalogPickerPage(backStack, viewModel, Route.CatalogPicker(CatalogKind.Vaccine))
            }
        }
        rule.waitForIdle()
        // The bundled CVX list populates with an empty query; first entry click chooses it.
        rule.onNodeWithText("Adenovirus types 4 and 7", substring = true).performClick()
        rule.waitForIdle()
        assertTrue(viewModel.vaccinationDraft.value.displayName.isNotBlank())
        assertEquals(Route.EditVaccination(), backStack.last())
    }

    @Test
    fun `today screen every quick stat row fires its action`() {
        var opened: HealthMetricConfig? = null
        var exercise = 0
        var sleep = 0
        val actions = object : TodayActions {
            override fun openSleepDetails() { sleep++ }
            override fun openExerciseDetails() { exercise++ }
            override fun openMetric(config: HealthMetricConfig) { opened = config }
        }
        val state = TodayUiState(
            steps = 100, activeCalories = 10, mindfulnessMinutes = 5, distanceKm = 1.0,
            floors = 2.0, hydrationMl = 500.0, heartRateMin = 50, heartRateMax = 100,
            elevationMeters = 20.0, wheelchairPushes = 3, exerciseMinutes = 30,
            metrics = MainPageMetrics(sleepMinutes = 420, bloodPressure = 120.0 to 80.0, spo2 = 99.0, rhr = 55),
        )
        rule.setContent { TodayScreen(state = state, actions = actions) }
        fun clickRow(label: String) {
            rule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
            val nodes = rule.onAllNodesWithText(label).fetchSemanticsNodes()
            // "Steps" also labels the activity ring; its metric row is the last match.
            rule.onAllNodesWithText(label)[nodes.size - 1].performClick()
        }
        // Steps is skipped: its label also titles the activity ring and its
        // unit repeats the label, so text clicks land on the ring, not the row.
        clickRow("Distance")
        assertEquals(HealthMetricConfig.DISTANCE, opened)
        clickRow("Floors")
        assertEquals(HealthMetricConfig.FLOORS, opened)
        clickRow("Elevation")
        assertEquals(HealthMetricConfig.ELEVATION, opened)
        clickRow("Wheelchair Pushes")
        assertEquals(HealthMetricConfig.WHEELCHAIR_PUSHES, opened)
        clickRow("Hydration")
        assertEquals(HealthMetricConfig.HYDRATION, opened)
        clickRow("Exercise")
        assertEquals(1, exercise)
        clickRow("Blood Pressure")
        assertEquals(HealthMetricConfig.BLOOD_PRESSURE, opened)
        clickRow("Oxygen Saturation")
        assertEquals(HealthMetricConfig.OXYGEN_SATURATION, opened)
        clickRow("Resting Heart Rate")
        assertEquals(HealthMetricConfig.RESTING_HEART_RATE, opened)
        assertEquals(0, sleep)
    }

    @Test
    fun `recipe management recipe click navigates to editor`() = runBlocking {
        HealthRepository.get(context).insertRecipe(Recipe(id = "interact-recipe-1", name = "Interact Soup"))
        HealthRepository.get(context).insertIngredient(Ingredient(id = "interact-ing-1", originalName = "Interact Carrot", nutritionData = NutritionData(calories = 40.0)))
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.RecipeManagement))
        rule.setContent { RecipeManagementPage(backStack, vm()) }
        rule.waitForIdle()
        rule.onNodeWithText("Interact Soup").assertIsDisplayed()
        rule.onNodeWithText("Interact Soup").performClick()
        val last = backStack.last()
        assertTrue(last is Route.RecipeEditor)
        assertEquals("interact-recipe-1", (last as Route.RecipeEditor).recipeId)
    }
}
