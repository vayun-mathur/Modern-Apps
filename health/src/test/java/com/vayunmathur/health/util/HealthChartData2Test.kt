package com.vayunmathur.health.util

import android.app.Application
import android.content.res.Resources
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
import com.vayunmathur.library.util.Tuple3
import com.vayunmathur.library.util.databases
import java.time.Duration
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthChartData2Test {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val resources: Resources get() = context.resources

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
    }

    private fun repo(): HealthRepository = HealthRepository.get(context)

    @Test
    fun `query chart pairs sums for bar and averages for line`() = runTest {
        val at = Instant.parse("2024-06-03T10:00:00Z")
        repo().upsert(listOf(
            Record(id = "chart2-steps", index = 0, type = RecordType.Steps, startTime = at, endTime = at, value = 1200.0),
            Record(id = "chart2-hr", index = 0, type = RecordType.HeartRate, startTime = at, endTime = at, value = 72.0),
        ))
        val start = kotlin.time.Instant.fromEpochMilliseconds(at.toEpochMilli() - 86_400_000)
        val end = kotlin.time.Instant.fromEpochMilliseconds(at.toEpochMilli() + 86_400_000)
        val (barPairs, _) = HealthChartData.queryChartPairs(HealthMetricConfig.STEPS, start, end, HealthAPI.PeriodType.Daily, HealthAPI.PeriodType.Daily)
        assertTrue(barPairs.any { it.second == 1200.0 })
        val (linePairs, _) = HealthChartData.queryChartPairs(HealthMetricConfig.HEART_RATE, start, end, HealthAPI.PeriodType.Daily, HealthAPI.PeriodType.Daily)
        assertTrue(linePairs.any { it.second == 72.0 })
        val (bpPairs, _) = HealthChartData.queryChartPairs(HealthMetricConfig.BLOOD_PRESSURE, start, end, HealthAPI.PeriodType.Daily, HealthAPI.PeriodType.Daily)
        assertTrue(bpPairs.isEmpty() || bpPairs.all { it.second >= 0.0 })
    }

    @Test
    fun `build history items for week month and year tabs`() {
        val tz = TimeZone.UTC
        val start = kotlin.time.Instant.fromEpochMilliseconds(Instant.parse("2024-06-02T00:00:00Z").toEpochMilli())
        val pairs = listOf(Tuple3(0L, 100.0, 50.0), Tuple3(1L, 200.0, 60.0))
        val weekPairs = listOf(
            Tuple3(LocalDate(2024, 6, 3).toEpochDays().toLong(), 100.0, 50.0),
            Tuple3(LocalDate(2024, 6, 4).toEpochDays().toLong(), 200.0, 60.0),
        )
        val week = HealthChartData.buildHistoryItems(HealthMetricConfig.STEPS, 1, LocalDate(2024, 6, 2), start, tz, resources, weekPairs)
        assertEquals(7, week.size)
        assertTrue(week.any { it.value == 200.0 })
        val weekLine = HealthChartData.buildHistoryItems(HealthMetricConfig.HEART_RATE, 1, LocalDate(2024, 6, 2), start, tz, resources, weekPairs)
        assertTrue(weekLine.size <= 7)
        val month = HealthChartData.buildHistoryItems(HealthMetricConfig.BLOOD_PRESSURE, 2, LocalDate(2024, 6, 1), start, tz, resources, pairs)
        assertEquals(2, month.size)
        assertEquals(50.0, month.last().secondaryValue)
        val year = HealthChartData.buildHistoryItems(HealthMetricConfig.STEPS, 3, LocalDate(2024, 1, 1), start, tz, resources, pairs)
        assertEquals(2, year.size)
        val day = HealthChartData.buildHistoryItems(HealthMetricConfig.STEPS, 0, LocalDate(2024, 6, 2), start, tz, resources, pairs)
        assertTrue(day.isEmpty())
    }

    @Test
    fun `compute recipe nutrition scales per 100g by grams used`() = runTest {
        val repo = repo()
        val ingredient = Ingredient(id = "chart2-ing", originalName = "Chart Flour", nutritionData = NutritionData(protein = 10.0, carbohydrates = 70.0, calories = 350.0))
        repo.insertIngredient(ingredient)
        val unit = ServingUnit(id = "chart2-unit", ingredientId = ingredient.id, name = "Gram", grams = 1.0)
        repo.insertServingUnit(unit)
        repo.insertRecipe(Recipe(id = "chart2-recipe", name = "Chart Bread"))
        repo.insertRecipeIngredient(RecipeIngredient(id = "chart2-ri", recipeId = "chart2-recipe", ingredientId = ingredient.id, quantity = 200.0, unitId = unit.id))
        val nutrition = HealthChartData.computeRecipeNutrition(repo, "chart2-recipe", 1.0)
        assertEquals(20.0, nutrition.protein)
        assertEquals(700.0, nutrition.calories)
        val missing = HealthChartData.computeRecipeNutrition(repo, "no-such-recipe", 1.0)
        assertEquals(0.0, missing.protein)
    }
}
