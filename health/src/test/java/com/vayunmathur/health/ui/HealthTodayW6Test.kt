package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.platform.HealthViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W6: Today and the per-metric detail pages with a week of seeded records,
 * so populated chart/list branches (not just empty states) render.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthTodayW6Test {

    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setup() {
        com.vayunmathur.health.data.ReferenceCatalog.init(context)
        com.vayunmathur.health.data.FoodDatabase.init(context)
        synchronized(databases) {
            if (databases[HealthDatabase::class] == null) {
                databases[HealthDatabase::class] = Room
                    .inMemoryDatabaseBuilder(context, HealthDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            }
        }
        seed()
    }

    private val seededIds = mutableListOf<String>()

    @kotlin.test.AfterTest
    fun cleanup() {
        // The in-memory database is shared with other test classes: remove
        // every seeded record so chart/empty-state tests see a clean store.
        runBlocking { HealthRepository.get(context).deleteByIds(seededIds) }
    }

    private fun seed() {
        val repo = HealthRepository.get(context)
        val now = Instant.now()
        runBlocking {
            var i = 0
            for (type in listOf(
                RecordType.Steps, RecordType.HeartRate, RecordType.Weight, RecordType.Sleep,
                RecordType.Exercise, RecordType.Hydration, RecordType.CaloriesTotal, RecordType.Distance,
            )) {
                for (day in 0 until 5) {
                    val start = now.minusSeconds(day * 86_400L + 3600)
                    val id = "w6-${type.name}-$day"
                    seededIds += id
                    repo.upsert(
                        listOf(
                            Record(
                                id = id, index = 0, type = type,
                                startTime = start, endTime = start.plusSeconds(1800),
                                value = 100.0 + day * 10 + i,
                            ),
                        ),
                    )
                    i++
                }
            }
        }
    }

    private fun vm() = HealthViewModel(context, HealthRepository.get(context))
    private fun stack() = NavBackStack<Route>(arrayOf(Route.Today))

    @Test
    fun today_withRecords_rendersMetricCards() {
        val s = stack()
        rule.setContent { TodayPage(s, vm()) }
        rule.waitForIdle()
        // Scroll through the dashboard; metric cards are populated.
        rule.onAllNodesWithText("Steps", substring = true).fetchSemanticsNodes().let {
            if (it.isNotEmpty()) rule.onAllNodesWithText("Steps", substring = true)[0].performScrollTo()
        }
        rule.waitForIdle()
    }

    @Test
    fun bodyPage_withRecords() {
        rule.setContent { BodyPage(stack(), vm()) }
        rule.waitForIdle()
    }

    @Test
    fun sleepPage_withRecords() {
        rule.setContent { SleepDetailsPage(stack(), vm()) }
        rule.waitForIdle()
    }

    @Test
    fun exercisePage_withRecords() {
        rule.setContent { ExerciseDetailsPage(stack(), vm()) }
        rule.waitForIdle()
    }

    @Test
    fun nutritionPage_withRecords() {
        rule.setContent { NutritionPage(stack(), vm()) }
        rule.waitForIdle()
    }
}
