package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
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
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NutritionDetailsDeleteTest {

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

    @Test
    fun `logged meal shows in breakdown with its macros`() = runBlocking<Unit> {
        val repo = HealthRepository.get(context)
        val now = Instant.now()
        repo.upsert(listOf(Record(
            id = "details-delete-meal", index = 0, type = RecordType.Nutrition, startTime = now, endTime = now, value = 320.0,
            nutritionData = NutritionData(calories = 320.0, protein = 18.0, carbohydrates = 30.0, fat = 10.0),
            metadata = "Details Delete Bowl",
        )))
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.NutritionFullBreakdown))
        rule.setContent { NutritionDetailsPage(backStack, HealthViewModel(context, repo)) }
        rule.waitForIdle()
        rule.onNodeWithText("Logged today").assertExists()
        rule.onNodeWithText("Details Delete Bowl").assertExists()
        rule.onNodeWithText("Nutrient breakdown").assertExists()
        assertTrue(repo.getRecordsFlow(RecordType.Nutrition).first().any { it.id == "details-delete-meal" })
    }
}
