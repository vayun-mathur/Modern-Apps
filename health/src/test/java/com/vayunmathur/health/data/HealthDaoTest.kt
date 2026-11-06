package com.vayunmathur.health.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DAO tests for the fitness-record and food tables: [HealthDao] against an
 * in-memory [HealthDatabase].
 *
 * These pin the query contracts the dashboard depends on: point-in-time
 * reads, range sums and averages feeding the charts, the day/hour bucket
 * groupings, and the recipe/ingredient CRUD behind the food logger.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthDaoTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db: HealthDatabase = Room.inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.healthDao()

    @AfterTest
    fun closeDb() {
        db.close()
    }

    private fun record(
        id: String,
        type: RecordType = RecordType.Steps,
        startMillis: Long = 1_000L,
        endMillis: Long = 2_000L,
        value: Double = 100.0,
        secondary: Double = 0.0,
    ) = Record(
        id = id,
        index = 0,
        type = type,
        startTime = java.time.Instant.ofEpochMilli(startMillis),
        endTime = java.time.Instant.ofEpochMilli(endMillis),
        value = value,
        secondaryValue = secondary,
    )

    private fun range() = kotlin.time.Instant.fromEpochMilliseconds(0) to
        kotlin.time.Instant.fromEpochMilliseconds(10_000L)

    // --- Records -----------------------------------------------------------------

    @Test
    fun `upsert replaces and deleteByIds removes`() = runTest {
        dao.upsert(listOf(record("r1", value = 1.0)))
        dao.upsert(listOf(record("r1", value = 2.0)))
        val (start, end) = range()
        assertEquals(2.0, dao.sumInRange(RecordType.Steps, start, end).first())
        dao.deleteByIds(listOf("r1"))
        assertEquals(0.0, dao.sumInRange(RecordType.Steps, start, end).first())
    }

    @Test
    fun `getLastRecord returns the latest by start time`() = runTest {
        dao.upsert(listOf(record("old", startMillis = 1_000L), record("new", startMillis = 5_000L)))
        assertEquals("new", dao.getLastRecord(RecordType.Steps)?.id)
        assertNull(dao.getLastRecord(RecordType.Sleep))
    }

    @Test
    fun `getRecordsFlow orders newest first`() = runTest {
        dao.upsert(listOf(record("a", startMillis = 1_000L), record("b", startMillis = 3_000L)))
        val ids = dao.getRecordsFlow(RecordType.Steps).first().map { it.id }
        assertEquals(listOf("b", "a"), ids)
    }

    @Test
    fun `sumInRange only counts the window`() = runTest {
        dao.upsert(
            listOf(
                record("in1", startMillis = 1_000L, endMillis = 2_000L, value = 10.0),
                record("in2", startMillis = 3_000L, endMillis = 4_000L, value = 20.0),
                // Starts inside but ends outside: excluded by the end bound.
                record("out", startMillis = 5_000L, endMillis = 50_000L, value = 100.0),
            )
        )
        val (start, end) = range()
        assertEquals(30.0, dao.sumInRange(RecordType.Steps, start, end).first())
    }

    @Test
    fun `nutrition sums read the calories column`() = runTest {
        val nutrition = record("n1", type = RecordType.Nutrition, value = 999.0)
            .copy(nutritionData = NutritionData(protein = 10.0, calories = 250.0))
        dao.upsert(listOf(nutrition))
        val (start, end) = range()
        // Nutrition sums the nutrition_calories column, not value.
        assertEquals(250.0, dao.sumInRange(RecordType.Nutrition, start, end).first())
        val totals = dao.sumNutritionInRange(RecordType.Nutrition, start, end).first()
        assertEquals(10.0, totals.protein)
        assertEquals(250.0, totals.calories)
        assertEquals(0.0, totals.fat)
    }

    @Test
    fun `min and max read the window`() = runTest {
        dao.upsert(
            listOf(
                record("a", startMillis = 1_000L, endMillis = 2_000L, value = 5.0),
                record("b", startMillis = 3_000L, endMillis = 4_000L, value = 15.0),
            )
        )
        val (start, end) = range()
        assertEquals(5.0, dao.minInRange(RecordType.Steps, start, end).first())
        assertEquals(15.0, dao.maxInRange(RecordType.Steps, start, end).first())
        assertNull(dao.minInRange(RecordType.Sleep, start, end).first())
        assertNull(dao.maxInRange(RecordType.Sleep, start, end).first())
    }

    @Test
    fun `getAllInRange returns the window contents`() = runTest {
        dao.upsert(
            listOf(
                record("a", startMillis = 1_000L, endMillis = 2_000L),
                record("b", startMillis = 30_000L, endMillis = 31_000L),
            )
        )
        val (start, end) = range()
        assertEquals(listOf("a"), dao.getAllInRange(RecordType.Steps, start, end).first().map { it.id })
    }

    @Test
    fun `daily sums group by calendar day`() = runTest {
        // Two records on consecutive UTC days (epoch millis chosen to straddle midnight UTC).
        val dayMs = 86_400_000L
        dao.upsert(
            listOf(
                record("d1", startMillis = dayMs + 1_000L, endMillis = dayMs + 2_000L, value = 10.0),
                record("d2", startMillis = 2 * dayMs + 1_000L, endMillis = 2 * dayMs + 2_000L, value = 20.0),
            )
        )
        val sums = dao.getDailySums(
            RecordType.Steps,
            kotlin.time.Instant.fromEpochMilliseconds(0),
            kotlin.time.Instant.fromEpochMilliseconds(3 * dayMs),
        )
        assertEquals(2, sums.size)
        assertEquals(10.0, sums[0].totalValue)
        assertEquals(20.0, sums[1].totalValue)
        assertTrue(sums[0].day < sums[1].day)
    }

    @Test
    fun `daily avgs average within the day`() = runTest {
        val dayMs = 86_400_000L
        dao.upsert(
            listOf(
                record("a", startMillis = dayMs + 1_000L, endMillis = dayMs + 2_000L, value = 10.0),
                record("b", startMillis = dayMs + 3_000L, endMillis = dayMs + 4_000L, value = 30.0),
            )
        )
        val avgs = dao.getDailyAvgs(
            RecordType.Steps,
            kotlin.time.Instant.fromEpochMilliseconds(0),
            kotlin.time.Instant.fromEpochMilliseconds(2 * dayMs),
        )
        assertEquals(1, avgs.size)
        assertEquals(20.0, avgs[0].totalValue)
    }

    @Test
    fun `hourly sums and avgs bucket by hour`() = runTest {
        val hourMs = 3_600_000L
        dao.upsert(
            listOf(
                record("a", startMillis = hourMs + 1_000L, endMillis = hourMs + 2_000L, value = 10.0),
                record("b", startMillis = hourMs + 3_000L, endMillis = hourMs + 4_000L, value = 30.0),
                record(
                    "c", startMillis = 2 * hourMs + 1_000L, endMillis = 2 * hourMs + 2_000L,
                    value = 50.0,
                ),
            )
        )
        val sums = dao.getHourlySums(RecordType.Steps, 0L, 3 * hourMs)
        assertEquals(2, sums.size)
        assertEquals(40.0, sums[0].totalValue)
        assertEquals(50.0, sums[1].totalValue)
        val avgs = dao.getHourlyAvgs(RecordType.Steps, 0L, 3 * hourMs)
        assertEquals(2, avgs.size)
        assertEquals(20.0, avgs[0].totalValue)
        assertEquals(50.0, avgs[1].totalValue)
    }

    // --- Ingredients and recipes ---------------------------------------------------

    @Test
    fun `ingredient crud and search`() = runTest {
        val ingredient = Ingredient(
            id = "i1", originalName = "Whole milk", nutritionData = NutritionData(calories = 60.0)
        )
        dao.insertIngredient(ingredient)
        assertEquals("i1", dao.getIngredient("i1")?.id)
        dao.updateIngredient(ingredient.copy(customName = "Milk"))
        assertEquals("Milk", dao.getIngredient("i1")?.displayName)
        assertEquals(1, dao.searchIngredients("milk").size)
        assertEquals(0, dao.searchIngredients("quinoa").size)
        assertEquals(1, dao.getAllIngredientsFlow().first().size)
        dao.deleteIngredient(ingredient)
        assertNull(dao.getIngredient("i1"))
    }

    @Test
    fun `recipes surface only when flagged`() = runTest {
        dao.insertIngredient(
            Ingredient(id = "plain", originalName = "Salt", nutritionData = NutritionData())
        )
        dao.insertIngredient(
            Ingredient(
                id = "rec", originalName = "Pancakes", isRecipe = true,
                nutritionData = NutritionData(),
            )
        )
        assertEquals(2, dao.getAllIngredientsFlow().first().size)
        assertEquals(listOf("rec"), dao.getIngredientsAsRecipesFlow().first().map { it.id })
    }

    @Test
    fun `recipe crud and serving units`() = runTest {
        val recipeId = "recipe-1"
        dao.insertRecipe(Recipe(id = recipeId, name = "Salad"))
        assertEquals("Salad", dao.getRecipe(recipeId)?.name)
        dao.updateRecipe(Recipe(id = recipeId, name = "Big salad"))
        assertEquals("Big salad", dao.getRecipe(recipeId)?.name)
        assertEquals(1, dao.getAllRecipesFlow().first().size)

        dao.insertIngredient(
            Ingredient(id = "i1", originalName = "Tomato", nutritionData = NutritionData())
        )
        val unit = ServingUnit(id = "u1", ingredientId = "i1", name = "Cup", grams = 150.0)
        dao.insertServingUnit(unit)
        assertEquals(listOf(unit), dao.getUnitsForIngredient("i1"))

        dao.insertRecipeIngredient(
            RecipeIngredient(id = "ri1", recipeId = recipeId, ingredientId = "i1", quantity = 2.0, unitId = "u1")
        )
        val rows = dao.getIngredientsForRecipe(recipeId)
        assertEquals(1, rows.size)
        assertEquals(2.0, rows[0].quantity)
        dao.deleteRecipeIngredient(rows[0])
        assertTrue(dao.getIngredientsForRecipe(recipeId).isEmpty())

        dao.deleteServingUnit(unit)
        assertTrue(dao.getUnitsForIngredient("i1").isEmpty())
        dao.deleteRecipe(Recipe(id = recipeId, name = "Big salad"))
        assertNull(dao.getRecipe(recipeId))
    }
}
