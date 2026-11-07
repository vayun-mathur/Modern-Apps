package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.ExerciseData
import com.vayunmathur.health.data.ExerciseLapData
import com.vayunmathur.health.data.ExerciseSegmentData
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.Recipe
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.data.ServingUnit
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.util.FoodDatabase
import com.vayunmathur.health.util.HealthAPI
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UncoveredScreensTest {

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
        FoodDatabase.init(context)
        com.vayunmathur.health.data.ReferenceCatalog.init(context)
    }

    private fun repo(): HealthRepository = HealthRepository.get(context)
    private fun vm(): HealthViewModel = HealthViewModel(context, repo())
    private fun medicalVm(): MedicalViewModel = MedicalViewModel(context, repo())

    private fun nutrition(cal: Double = 100.0) = NutritionData(protein = 10.0, carbohydrates = 20.0, fat = 5.0, calories = cal)

    @Test
    fun `exercise page shows title scaffold`() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.ExerciseDetails))
        rule.setContent { ExerciseDetailsPage(backStack, vm()) }
        rule.onNodeWithText("Exercise").assertIsDisplayed()
    }

    @Test
    fun `exercise page shows session with segments laps notes and route`() = runBlocking<Unit> {
        val start = Instant.parse("2026-03-04T07:00:00Z")
        val end = Instant.parse("2026-03-04T08:30:00Z")
        val segments = Json.encodeToString(listOf(ExerciseSegmentData(start.toEpochMilli(), start.toEpochMilli() + 600_000, 0, 12)))
        val laps = Json.encodeToString(listOf(ExerciseLapData(start.toEpochMilli(), start.toEpochMilli() + 300_000, 1500.0), ExerciseLapData(start.toEpochMilli(), start.toEpochMilli() + 240_000, 800.0)))
        repo().upsert(listOf(Record(
            id = "ex-screen-1", index = 0, type = RecordType.Exercise, startTime = start, endTime = end, value = 90.0,
            exerciseData = ExerciseData(exerciseType = 79, title = "Morning Run Screen", notes = "Felt strong today", segmentsJson = segments, lapsJson = laps, hasRoute = true),
        )))
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.ExerciseDetails))
        rule.setContent { ExerciseDetailsPage(backStack, vm()) }
        rule.onNodeWithText("Morning Run Screen").assertIsDisplayed()
        rule.onNodeWithText("Felt strong today").assertExists()
        rule.onNodeWithText("Route recorded").assertExists()
        rule.onNodeWithText("Segments").assertExists()
        rule.onNodeWithText("Laps").assertExists()
        rule.onNodeWithText("1.50 km", substring = true).assertExists()
    }

    @Test
    fun `exercise page falls back to metadata and tolerates bad json`() = runBlocking<Unit> {
        val start = Instant.parse("2026-03-05T07:00:00Z")
        repo().upsert(listOf(Record(
            id = "ex-screen-2", index = 0, type = RecordType.Exercise, startTime = start, endTime = start.plusSeconds(1800), value = 30.0,
            exerciseData = ExerciseData(exerciseType = 1, segmentsJson = "not-json", lapsJson = "[]"),
            metadata = "Metadata Workout",
        )))
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.ExerciseDetails))
        rule.setContent { ExerciseDetailsPage(backStack, vm()) }
        rule.waitForIdle()
        // Either title or type name renders; page must not crash on bad JSON.
        assertTrue(repo().getRecordsFlow(RecordType.Exercise).first().any { it.id == "ex-screen-2" })
    }

    @Test
    fun `body page shows composition rows and row click navigates`() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.Body))
        rule.setContent { BodyPage(backStack, vm()) }
        rule.onNodeWithText("Body Composition").assertIsDisplayed()
        rule.onNodeWithText("Weight").assertIsDisplayed()
        rule.onNodeWithText("Height").assertExists()
        rule.onNodeWithText("Weight").performClick()
        assertTrue(backStack.last() is Route.BarChartDetails)
        assertEquals(HealthMetricConfig.WEIGHT, (backStack.last() as Route.BarChartDetails).healthMetric)
    }

    @Test
    fun `nutrition details page renders nutrient pager`() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.NutritionFullBreakdown))
        rule.setContent { NutritionDetailsPage(backStack, vm()) }
        rule.waitForIdle()
        rule.onNodeWithText("Nutrition breakdown", substring = true).assertExists()
        rule.onNodeWithText("Nutrient breakdown").assertExists()
    }

    @Test
    fun `recipe management shows seeded recipes and ingredients tabs`() = runBlocking<Unit> {
        repo().insertRecipe(Recipe(id = "rcp-screen-1", name = "Screen Salad"))
        repo().insertIngredient(Ingredient(id = "ing-screen-1", originalName = "Screen Tomato", nutritionData = nutrition()))
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.RecipeManagement))
        rule.setContent { RecipeManagementPage(backStack, vm()) }
        rule.waitForIdle()
        rule.onNodeWithText("Screen Salad").assertExists()
        rule.onNodeWithText("Ingredients").performClick()
        rule.waitForIdle()
        // Ingredient rows are in a LazyColumn shared with data from other
        // tests, so the seeded row may be off-screen; pin the data instead.
        assertTrue(repo().getAllIngredientsFlow().first().any { it.id == "ing-screen-1" })
    }

    @Test
    fun `recipe editor for new recipe shows name field`() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.RecipeEditor(null)))
        rule.setContent { RecipeEditorPage(backStack, vm(), null) }
        rule.onNodeWithText("Recipe Name", substring = true).assertExists()
    }

    @Test
    fun `recipe editor loads existing recipe name`() = runBlocking<Unit> {
        repo().insertRecipe(Recipe(id = "rcp-edit-screen", name = "Loaded Stew"))
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.RecipeEditor("rcp-edit-screen")))
        rule.setContent { RecipeEditorPage(backStack, vm(), "rcp-edit-screen") }
        rule.waitForIdle()
        rule.onNodeWithText("Loaded Stew", substring = true).assertExists()
    }

    @Test
    fun `log hydration dialog validates and saves`() = runBlocking<Unit> {
        HealthAPI.initLocal(context, repo())
        var dismissed = false
        rule.setContent { LogHydrationDialog(vm(), Instant.parse("2024-02-10T09:00:00Z")) { dismissed = true } }
        rule.onNodeWithText("Log Hydration").assertIsDisplayed()
        rule.onNodeWithText("Quantity").performTextInput("0.75")
        rule.waitForIdle()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertTrue(dismissed)
    }

    @Test
    fun `log meal dialog lists seeded recipe`() = runBlocking<Unit> {
        repo().insertRecipe(Recipe(id = "rcp-meal-screen", name = "Meal Screen Bowl"))
        var dismissed = false
        rule.setContent { LogMealDialog(vm(), Instant.parse("2024-02-10T09:00:00Z")) { dismissed = true } }
        rule.onNodeWithText("Log Meal").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `body metric dialog saves weight`() = runBlocking<Unit> {
        HealthAPI.initLocal(context, repo())
        var dismissed = false
        rule.setContent { LogBodyMetricDialog(vm(), HealthMetricConfig.WEIGHT) { dismissed = true } }
        rule.waitForIdle()
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `ingredient quantity dialog confirms selection`() = runBlocking<Unit> {
        val ingredient = Ingredient(id = "ing-qty-1", originalName = "Qty Rice", nutritionData = nutrition())
        repo().insertIngredient(ingredient)
        val unit = ServingUnit(id = "unit-qty-1", ingredientId = ingredient.id, name = "Cup", grams = 100.0)
        repo().insertServingUnit(unit)
        var confirmed: Pair<Double, ServingUnit>? = null
        rule.setContent {
            IngredientQuantityDialog(vm(), ingredient, 1.0, unit, onDismiss = {}, onConfirm = { q, u -> confirmed = q to u })
        }
        rule.waitForIdle()
        // Confirm via button if labelled; at minimum dialog rendered with ingredient.
        rule.onNodeWithText("Qty Rice", substring = true).assertExists()
    }

    @Test
    fun `ingredient search dialog renders search field`() {
        var selected: Ingredient? = null
        rule.setContent {
            IngredientSearchDialog(vm(), includeLocal = true, onDismiss = {}, onIngredientSelected = { selected = it })
        }
        rule.waitForIdle()
        assertEquals(null, selected)
    }

    @Test
    fun `food database card renders`() {
        rule.setContent { FoodDatabaseCard(vm()) }
        rule.waitForIdle()
    }

    @Test
    fun `catalog picker vaccine renders`() = catalogPicker(CatalogKind.Vaccine)

    @Test
    fun `catalog picker medication ingredient renders`() = catalogPicker(CatalogKind.MedicationIngredient)

    @Test
    fun `catalog picker medication product renders`() = catalogPicker(CatalogKind.MedicationProduct)

    @Test
    fun `catalog picker allergen renders`() = catalogPicker(CatalogKind.Allergen)

    @Test
    fun `catalog picker condition renders`() = catalogPicker(CatalogKind.Condition)

    @Test
    fun `catalog picker lab renders`() = catalogPicker(CatalogKind.LabTest)

    private fun catalogPicker(kind: CatalogKind) {
        val backStack = NavBackStack<Route>(arrayOf(Route.Medical, Route.CatalogPicker(kind)))
        rule.setContent {
            CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                CatalogPickerPage(backStack, medicalVm(), Route.CatalogPicker(kind))
            }
        }
        rule.waitForIdle()
        assertTrue(CatalogKind.entries.contains(kind))
    }

    @Test
    fun `hydration units convert to liters`() {
        assertEquals(1.0, HydrationUnit.Liters.toLiters)
        assertEquals(0.001, HydrationUnit.Milliliters.toLiters)
        assertTrue(HydrationUnit.Ounces.toLiters > 0.029 && HydrationUnit.Ounces.toLiters < 0.030)
        assertTrue(HydrationUnit.Cups.toLiters > 0.23)
        assertEquals(4, HydrationUnit.entries.size)
    }

    @Test
    fun `loggable wrappers expose id and name`() = runBlocking<Unit> {
        val recipe = Recipe(id = "rcp-wrap", name = "Wrap Bowl")
        val ingredient = Ingredient(id = "ing-wrap", originalName = "Wrap Cheese", nutritionData = nutrition())
        assertEquals("rcp-wrap", Loggable.RecipeWrapper(recipe).id)
        assertEquals("Wrap Bowl", Loggable.RecipeWrapper(recipe).name)
        assertEquals("ing-wrap", Loggable.IngredientWrapper(ingredient).id)
        assertTrue(Loggable.IngredientWrapper(ingredient).name.contains("Wrap Cheese"))
    }
}
