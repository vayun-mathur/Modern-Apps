package com.vayunmathur.health.util

import android.app.Application
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
import com.vayunmathur.health.ui.HealthMetricConfig
import com.vayunmathur.health.ui.HistoryItem
import com.vayunmathur.library.util.Tuple3
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the pure chart/nutrition math in [HealthChartData]: tab ranges,
 * week-series slotting, dashboard aggregation, goal flags, sleep recency,
 * and recipe nutrition scaling.
 *
 * Everything here is a deterministic function of its arguments (the two
 * `suspend` helpers read Room through the repository, seeded in-memory), so
 * these run as plain unit tests with no ViewModel or Health Connect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthChartDataTest {

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

    private fun nutrition(calories: Double = 100.0) = NutritionData(
        protein = 10.0, carbohydrates = 20.0, fat = 5.0, fiber = 2.0,
        sugar = 3.0, sodium = 50.0, calories = calories,
    )

    @Test
    fun `day tab spans exactly the anchor date`() {
        val anchor = LocalDate(2026, 3, 4)
        val range = HealthChartData.resolveChartRange(anchor, 0)
        assertEquals(anchor, range.first)
        assertEquals(LocalDate(2026, 3, 5), range.second)
        assertEquals(HealthAPI.PeriodType.Hourly, range.third)
    }

    @Test
    fun `week tab starts on sunday`() {
        // Wednesday Mar 4 2026 -> week starts Sunday Mar 1.
        val range = HealthChartData.resolveChartRange(LocalDate(2026, 3, 4), 1)
        assertEquals(LocalDate(2026, 3, 1), range.first)
        assertEquals(LocalDate(2026, 3, 8), range.second)
        assertEquals(HealthAPI.PeriodType.Daily, range.third)
    }

    @Test
    fun `month tab spans the calendar month`() {
        val range = HealthChartData.resolveChartRange(LocalDate(2026, 3, 15), 2)
        assertEquals(LocalDate(2026, 3, 1), range.first)
        assertEquals(LocalDate(2026, 4, 1), range.second)
    }

    @Test
    fun `year tab spans the calendar year`() {
        val range = HealthChartData.resolveChartRange(LocalDate(2026, 3, 15), 3)
        assertEquals(LocalDate(2026, 1, 1), range.first)
        assertEquals(LocalDate(2027, 1, 1), range.second)
        assertEquals(HealthAPI.PeriodType.Monthly, range.third)
    }

    @Test
    fun `week series keeps every value in its own slot`() {
        val weekStart = LocalDate(2026, 3, 1) // Sunday
        val tuesday = LocalDate(2026, 3, 3)
        val pairs = listOf(Tuple3(tuesday.toEpochDays().toLong(), 5000.0, 0.0))
        val (mapped, _) = HealthChartData.mapChartSeries(
            HealthMetricConfig.STEPS, 1, weekStart, pairs, use24Hour = true,
        )
        assertEquals(7, mapped.size)
        // Sunday has no data (gap), Tuesday keeps its value.
        assertNull(mapped[0].second)
        assertEquals(5000.0, mapped[2].second)
    }

    @Test
    fun `non-week tabs map pairs in order`() {
        val pairs = listOf(
            Tuple3(0L, 100.0, 0.0),
            Tuple3(6L, 200.0, 0.0),
        )
        val (mapped, secondary) = HealthChartData.mapChartSeries(
            HealthMetricConfig.STEPS, 0, LocalDate(2026, 3, 4), pairs, use24Hour = true,
        )
        assertEquals(2, mapped.size)
        assertEquals(100.0, mapped[0].second)
        assertEquals(200.0, mapped[1].second)
        // Steps is not dual-series: no secondary chart.
        assertNull(secondary)
    }

    @Test
    fun `dual series maps the secondary values`() {
        val pairs = listOf(Tuple3(0L, 120.0, 80.0))
        val (_, secondary) = HealthChartData.mapChartSeries(
            HealthMetricConfig.BLOOD_PRESSURE, 0, LocalDate(2026, 3, 4), pairs, use24Hour = true,
        )
        assertNotNull(secondary)
        assertEquals(80.0, secondary.first().second)
    }

    @Test
    fun `dashboard totals sum the history off the day tab`() {
        val history = listOf(
            HistoryItem("Mon", 100.0, null, "steps", false, false),
            HistoryItem("Tue", 200.0, null, "steps", false, false),
        )
        val data = HealthChartData.buildDashboardData(
            HealthMetricConfig.STEPS, 1, emptyList(), emptyList(), null, history,
        )
        assertEquals(300.0, data.totalValue)
        assertEquals(150.0, data.dailyAverage)
        assertEquals(7, data.totalBarCount)
    }

    @Test
    fun `dashboard on the day tab sums raw pairs`() {
        val pairs = listOf(Tuple3(0L, 100.0, 0.0), Tuple3(1L, 50.0, 0.0))
        val data = HealthChartData.buildDashboardData(
            HealthMetricConfig.STEPS, 0, pairs,
            listOf("a" to 100.0, "b" to 50.0), null, emptyList(),
        )
        assertEquals(150.0, data.totalValue)
        assertEquals(150.0, data.dailyAverage)
        assertEquals(2, data.totalBarCount)
    }

    @Test
    fun `equal values widen the range so the axis has span`() {
        val data = HealthChartData.buildDashboardData(
            HealthMetricConfig.STEPS, 0, emptyList(),
            listOf("a" to 100.0, "b" to 100.0), null, emptyList(),
        )
        val range = data.primaryRange
        assertNotNull(range)
        assertTrue(range.endInclusive > range.start)
    }

    @Test
    fun `empty chart has no range`() {
        val data = HealthChartData.buildDashboardData(
            HealthMetricConfig.STEPS, 0, emptyList(), emptyList(), null, emptyList(),
        )
        assertNull(data.primaryRange)
        assertEquals(0.0, data.totalValue)
    }

    @Test
    fun `week history flags goals and reverses newest first`() {
        val weekStart = LocalDate(2026, 3, 1)
        val monday = LocalDate(2026, 3, 2)
        val history = HealthChartData.buildHistoryItems(
            HealthMetricConfig.STEPS, 1, weekStart,
            kotlin.time.Instant.fromEpochMilliseconds(0),
            TimeZone.currentSystemDefault(), context.resources,
            listOf(Tuple3(monday.toEpochDays().toLong(), 12_000.0, 0.0)),
        )
        assertEquals(7, history.size)
        val mondayRow = history.first { it.value == 12_000.0 }
        assertTrue(mondayRow.isGoalMet)
        assertTrue(history.first { it.value == 0.0 }.isGoalMet.not())
    }

    @Test
    fun `day tab builds no history rows`() {
        val history = HealthChartData.buildHistoryItems(
            HealthMetricConfig.STEPS, 0, LocalDate(2026, 3, 4),
            kotlin.time.Instant.fromEpochMilliseconds(0),
            TimeZone.currentSystemDefault(), context.resources,
            listOf(Tuple3(0L, 100.0, 0.0)),
        )
        assertTrue(history.isEmpty())
    }

    @Test
    fun `last night sleep returns null with no records`() = runTest {
        val repo = repository()
        HealthAPI.initLocal(context, repo)
        repo.deleteByIds(listOf("sleep-chart-1", "sleep-chart-old"))
        assertNull(HealthChartData.lastNightSleepMinutes(java.time.Duration.ofHours(12), 60L))
    }

    @Test
    fun `last night sleep converts hours to minutes`() = runTest {
        val repo = repository()
        HealthAPI.initLocal(context, repo)
        val now = Instant.now()
        repo.upsert(
            listOf(
                Record(
                    id = "sleep-chart-1", index = 0, type = RecordType.Sleep,
                    startTime = now.minusSeconds(8 * 3600), endTime = now,
                    value = 7.5, metadata = "Sleep",
                )
            )
        )
        assertEquals(450L, HealthChartData.lastNightSleepMinutes(java.time.Duration.ofHours(24), 60L))
    }

    @Test
    fun `stale sleep returns null past the lookback`() = runTest {
        val repo = repository()
        HealthAPI.initLocal(context, repo)
        val old = Instant.now().minusSeconds(72 * 3600)
        repo.upsert(
            listOf(
                Record(
                    id = "sleep-chart-old", index = 0, type = RecordType.Sleep,
                    startTime = old.minusSeconds(8 * 3600), endTime = old,
                    value = 7.5, metadata = "Sleep",
                )
            )
        )
        assertNull(HealthChartData.lastNightSleepMinutes(java.time.Duration.ofHours(12), 60L))
    }

    @Test
    fun `recipe nutrition scales per-100g facts to grams used`() = runTest {
        val repo = repository()
        val ingredient = Ingredient(id = "ing-chart-1", originalName = "Oats", nutritionData = nutrition(calories = 400.0))
        val unit = ServingUnit(id = "unit-chart-1", ingredientId = ingredient.id, name = "Gram", grams = 1.0)
        repo.insertIngredient(ingredient)
        repo.insertServingUnit(unit)
        repo.insertRecipe(Recipe(id = "rcp-chart-1", name = "Bowl"))
        repo.insertRecipeIngredient(
            RecipeIngredient(id = "ri-chart-1", recipeId = "rcp-chart-1", ingredientId = ingredient.id, quantity = 50.0, unitId = unit.id)
        )
        val scaled = HealthChartData.computeRecipeNutrition(repo, "rcp-chart-1", 1.0)
        // 50g of a 400 kcal/100g ingredient = 200 kcal.
        assertEquals(200.0, scaled.calories)
    }

    @Test
    fun `recipe nutrition multiplies by the logged quantity`() = runTest {
        val repo = repository()
        val total = HealthChartData.computeRecipeNutrition(repo, "rcp-chart-1", 2.0)
        assertEquals(400.0, total.calories)
    }
}
