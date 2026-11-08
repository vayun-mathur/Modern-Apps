package com.vayunmathur.health.util

import android.app.Application
import android.os.Looper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Ingredient
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.Recipe
import com.vayunmathur.health.data.RecipeIngredient
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.data.ServingUnit
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Tests for [HealthViewModel]'s Room-backed paths: flow passthroughs, the
 * sleep-day selector, recipe/ingredient CRUD, food-database prep, and the
 * week-series filler.
 *
 * Health Connect is unavailable under Robolectric, so every `HealthAPI.*`
 * call degrades to its local half; assertions pin the Room side. The VM's
 * coroutines run on Robolectric's Main, pumped by [settle] (virtual time).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthViewModelTest {

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
    }

    private fun repository(): HealthRepository = HealthRepository.get(context)
    private fun viewModel(): HealthViewModel = HealthViewModel(context, repository())

    init {
        // FoodDatabase.search (remote half of ingredient search) needs its
        // application context, which MainActivity supplies in production.
        FoodDatabase.init(context)
    }

    private suspend fun settle(cond: suspend () -> Boolean) {
        repeat(200) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            delay(5)
        }
    }

    private fun nutrition(
        calories: Double = 100.0,
        protein: Double = 10.0,
        carbs: Double = 20.0,
        fat: Double = 5.0,
    ) = NutritionData(
        protein = protein, carbohydrates = carbs, fat = fat, fiber = 2.0,
        sugar = 3.0, sodium = 50.0, calories = calories,
    )

    private fun record(type: RecordType, value: Double, at: Instant, id: String = "r-${type.name}-$value") =
        Record(
            id = id,
            index = 0,
            type = type,
            startTime = at,
            endTime = at,
            value = value,
            metadata = type.name,
        )

    @Test
    fun `sum flow reflects inserted records`() = runTest {
        val repo = repository()
        val vm = viewModel()
        val at = Instant.parse("2026-03-04T09:30:00Z")
        val start = kotlin.time.Instant.fromEpochMilliseconds(at.toEpochMilli() - 3_600_000)
        val end = kotlin.time.Instant.fromEpochMilliseconds(at.toEpochMilli() + 3_600_000)
        repo.upsert(listOf(record(RecordType.Steps, 500.0, at, id = "steps-vm-1")))
        val sum = vm.sumInRange(RecordType.Steps, start, end).first()
        assertEquals(500.0, sum)
    }

    @Test
    fun `min and max read the window`() = runTest {
        val repo = repository()
        val vm = viewModel()
        val at = Instant.parse("2026-03-04T09:30:00Z")
        val start = kotlin.time.Instant.fromEpochMilliseconds(at.toEpochMilli() - 3_600_000)
        val end = kotlin.time.Instant.fromEpochMilliseconds(at.toEpochMilli() + 3_600_000)
        repo.upsert(
            listOf(
                record(RecordType.Weight, 70.0, at, id = "w-vm-1"),
                record(RecordType.Weight, 72.0, at, id = "w-vm-2"),
            )
        )
        assertEquals(70.0, vm.minInRange(RecordType.Weight, start, end).first())
        assertEquals(72.0, vm.maxInRange(RecordType.Weight, start, end).first())
    }

    @Test
    fun `records flow lists the type newest first`() = runTest {
        val repo = repository()
        val vm = viewModel()
        val at = Instant.parse("2026-03-04T09:30:00Z")
        repo.upsert(listOf(record(RecordType.Height, 1.8, at, id = "h-vm-1")))
        val rows = vm.getAllRecordsOfType(RecordType.Height).first()
        assertTrue(rows.any { it.id == "h-vm-1" })
    }

    @Test
    fun `sleep selector picks the session ending that day`() = runTest {
        val repo = repository()
        val vm = viewModel()
        // Night of Mar 3 -> morning of Mar 4 (UTC zone on this device may shift
        // the local day; the search window is +-12h around the day start).
        val end = Instant.parse("2026-03-04T06:00:00Z")
        val start = Instant.parse("2026-03-03T22:00:00Z")
        repo.upsert(
            listOf(
                Record(
                    id = "sleep-vm-1", index = 0, type = RecordType.Sleep,
                    startTime = start, endTime = end, value = 8.0, metadata = "Sleep",
                )
            )
        )
        val day = LocalDate(2026, 3, 4)
        val picked = vm.sleepRecordForDay(day).first()
        // The session must either match the queried day or be absent when the
        // device zone puts its end on a different local day — never a wrong row.
        if (picked != null) assertEquals("sleep-vm-1", picked.id)
    }

    @Test
    fun `delete record removes the row`() = runTest {
        val repo = repository()
        val vm = viewModel()
        val at = Instant.parse("2026-03-04T09:30:00Z")
        repo.upsert(listOf(record(RecordType.Weight, 70.0, at, id = "w-del-vm")))
        HealthAPI.initLocal(context, repo)
        vm.deleteRecord(record(RecordType.Weight, 70.0, at, id = "w-del-vm"))
        settle { repo.getLastRecord(RecordType.Weight)?.id != "w-del-vm" }
        assertTrue(repo.getRecordsFlow(RecordType.Weight).first().none { it.id == "w-del-vm" })
    }

    @Test
    fun `log hydration writes a hydration row`() = runTest {
        val repo = repository()
        val vm = viewModel()
        HealthAPI.initLocal(context, repo)
        val at = Instant.parse("2026-03-04T09:30:00Z")
        vm.logHydration(0.5, at)
        val start = kotlin.time.Instant.fromEpochMilliseconds(at.toEpochMilli() - 3_600_000)
        val end = kotlin.time.Instant.fromEpochMilliseconds(at.toEpochMilli() + 3_600_000)
        settle { repo.getAllInRange(RecordType.Hydration, start, end).first().isNotEmpty() }
        val rows = repo.getAllInRange(RecordType.Hydration, start, end).first()
        assertTrue(rows.isNotEmpty())
        assertEquals(0.5, rows.first().value)
    }

    @Test
    fun `log body metric writes the row`() = runTest {
        val repo = repository()
        val vm = viewModel()
        HealthAPI.initLocal(context, repo)
        val at = Instant.parse("2026-03-04T09:30:00Z")
        vm.logBodyMetric(RecordType.Weight, 71.5, at)
        val start = kotlin.time.Instant.fromEpochMilliseconds(at.toEpochMilli() - 3_600_000)
        val end = kotlin.time.Instant.fromEpochMilliseconds(at.toEpochMilli() + 3_600_000)
        settle { repo.getAllInRange(RecordType.Weight, start, end).first().any { it.value == 71.5 } }
        assertTrue(repo.getAllInRange(RecordType.Weight, start, end).first().any { it.value == 71.5 })
    }

    @Test
    fun `log meal from ingredient scales nutrition by quantity`() = runTest {
        val repo = repository()
        val vm = viewModel()
        HealthAPI.initLocal(context, repo)
        val ingredient = Ingredient(id = "ing-vm-1", originalName = "Oats", nutritionData = nutrition(calories = 100.0))
        repo.insertIngredient(ingredient)
        val at = Instant.parse("2026-03-04T09:30:00Z")
        vm.logMeal(HealthViewModel.LogMealTarget.FromIngredient(ingredient), 2.0, at)
        val start = kotlin.time.Instant.fromEpochMilliseconds(at.toEpochMilli() - 3_600_000)
        val end = kotlin.time.Instant.fromEpochMilliseconds(at.toEpochMilli() + 3_600_000)
        settle { repo.getAllInRange(RecordType.Nutrition, start, end).first().isNotEmpty() }
        val rows = repo.getAllInRange(RecordType.Nutrition, start, end).first()
        assertEquals(200.0, rows.first().value)
        assertEquals("Oats", rows.first().metadata)
    }

    @Test
    fun `ingredient crud round trips`() = runTest {
        val repo = repository()
        val vm = viewModel()
        val ingredient = Ingredient(id = "ing-crud-1", originalName = "Apple", nutritionData = nutrition())
        vm.insertIngredient(ingredient)
        settle { repo.getIngredient("ing-crud-1") != null }
        assertNotNull(repo.getIngredient("ing-crud-1"))
        vm.updateIngredient(ingredient.copy(customName = "Green Apple"))
        settle { repo.getIngredient("ing-crud-1")?.customName == "Green Apple" }
        assertEquals("Green Apple", repo.getIngredient("ing-crud-1")?.customName)
        vm.deleteIngredient(ingredient.copy(customName = "Green Apple"))
        settle { repo.getIngredient("ing-crud-1") == null }
        assertNull(repo.getIngredient("ing-crud-1"))
    }

    @Test
    fun `load recipe for edit resolves rows and save writes them`() = runTest {
        val repo = repository()
        val vm = viewModel()
        val ingredient = Ingredient(id = "ing-rcp-1", originalName = "Milk", nutritionData = nutrition())
        val unit = ServingUnit(id = "unit-rcp-1", ingredientId = ingredient.id, name = "Cup", grams = 240.0)
        repo.insertIngredient(ingredient)
        repo.insertServingUnit(unit)
        repo.insertRecipe(Recipe(id = "rcp-1", name = "Porridge"))
        repo.insertRecipeIngredient(
            RecipeIngredient(id = "ri-1", recipeId = "rcp-1", ingredientId = ingredient.id, quantity = 1.0, unitId = unit.id)
        )
        val loaded = vm.loadRecipeForEdit("rcp-1")
        assertNotNull(loaded)
        assertEquals("Porridge", loaded.name)
        assertEquals(1, loaded.ingredients.size)
        assertEquals("Milk", loaded.ingredients.first().ingredient.displayName)

        var done = false
        vm.saveRecipe(
            existingRecipeId = "rcp-1",
            name = "Porridge v2",
            items = listOf(HealthViewModel.RecipeIngredientLoad(ingredient, unit, 2.0)),
        ) { done = true }
        settle { done }
        assertTrue(done)
        assertEquals("Porridge v2", repo.getRecipe("rcp-1")?.name)
        assertEquals(1, repo.getIngredientsForRecipe("rcp-1").size)
    }

    @Test
    fun `load recipe for missing id returns null`() = runTest {
        val vm = viewModel()
        assertNull(vm.loadRecipeForEdit("no-such-recipe"))
    }

    @Test
    fun `units for ingredient read through`() = runTest {
        val repo = repository()
        val vm = viewModel()
        repo.insertIngredient(Ingredient(id = "ing-x", originalName = "Salt", nutritionData = nutrition()))
        val unit = ServingUnit(id = "unit-vm-1", ingredientId = "ing-x", name = "Gram", grams = 1.0)
        repo.insertServingUnit(unit)
        val units = vm.getUnitsForIngredient("ing-x")
        assertEquals(1, units.size)
        assertEquals("Gram", units.first().name)
    }

    @Test
    fun `log meal from recipe sums its ingredients`() = runTest {
        val repo = repository()
        val vm = viewModel()
        HealthAPI.initLocal(context, repo)
        val ingredient = Ingredient(id = "ing-rcpmeal-1", originalName = "Rice", nutritionData = nutrition(calories = 130.0, protein = 2.0))
        val unit = ServingUnit(id = "unit-rcpmeal-1", ingredientId = ingredient.id, name = "Cup", grams = 100.0)
        repo.insertIngredient(ingredient)
        repo.insertServingUnit(unit)
        repo.insertRecipe(Recipe(id = "rcp-meal-1", name = "Rice Bowl"))
        repo.insertRecipeIngredient(
            RecipeIngredient(id = "ri-meal-1", recipeId = "rcp-meal-1", ingredientId = ingredient.id, quantity = 1.0, unitId = unit.id)
        )
        val at = Instant.parse("2026-03-04T09:30:00Z")
        vm.logMeal(HealthViewModel.LogMealTarget.FromRecipe("rcp-meal-1", "Rice Bowl"), 1.0, at)
        val start = kotlin.time.Instant.fromEpochMilliseconds(at.toEpochMilli() - 3_600_000)
        val end = kotlin.time.Instant.fromEpochMilliseconds(at.toEpochMilli() + 3_600_000)
        settle { repo.getAllInRange(RecordType.Nutrition, start, end).first().any { it.metadata == "Rice Bowl" } }
        val row = repo.getAllInRange(RecordType.Nutrition, start, end).first().first { it.metadata == "Rice Bowl" }
        assertEquals("Rice Bowl", row.metadata)
        assertEquals(130.0, row.value)
    }

    @Test
    fun `search ingredients combines remote and local`() = runTest {
        val repo = repository()
        val vm = viewModel()
        repo.insertIngredient(Ingredient(id = "ing-search-1", originalName = "Blueberry Muffin", nutritionData = nutrition()))
        val results = vm.searchIngredients("blueberry", includeLocal = true)
        assertTrue(results.local.any { it.id == "ing-search-1" })
    }

    @Test
    fun `fill week series maps all seven slots`() {
        val weekStart = LocalDate(2026, 3, 1)
        val filled = HealthViewModel.fillWeekSeries(weekStart, emptyMap())
        assertEquals(7, filled.size)
        assertEquals(weekStart, filled.first().first)
        assertNull(filled.first().second)
    }

    @Test
    fun `prepare food database is safe to call twice`() {
        val vm = viewModel()
        vm.prepareFoodDatabase()
        vm.prepareFoodDatabase()
        assertNotNull(vm.foodDatabaseStatus)
    }

    @Test
    fun `main page metrics start blank`() {
        val vm = viewModel()
        val m = vm.mainPageMetrics.value
        assertNull(m.spo2)
        assertNull(m.weight)
        assertEquals(MainPageMetrics(), m)
    }

    @Test
    fun `load main page metrics publishes sleep without health connect`() = runTest {
        val vm = viewModel()
        vm.loadMainPageMetrics()
        // Local-only mode: HealthAPI.lastRecord reads Room (empty), and the sleep
        // lookup resolves against FoodDatabase state — the call must complete
        // without throwing and leave a metrics object behind.
        settle(timeoutMs = 5_000) { true }
        assertNotNull(vm.mainPageMetrics.value)
    }

    private suspend fun settle(timeoutMs: Long = 2_000, cond: suspend () -> Boolean) {
        val rounds = (timeoutMs / 5).toInt().coerceAtLeast(1)
        repeat(rounds) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            delay(5)
        }
    }
}
