package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.util.FoodDatabase
import com.vayunmathur.health.util.HealthAPI
import com.vayunmathur.health.util.HealthViewModel
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BarChartBinderTest {

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
        runBlocking {
            val repo = HealthRepository.get(context)
            repo.getLastRecord(RecordType.Steps)
            val at = Instant.now()
            repo.upsert(listOf(Record(id = "binder-steps-1", index = 0, type = RecordType.Steps, startTime = at, endTime = at, value = 3000.0, metadata = "Steps")))
        }
    }

    private fun vm(): HealthViewModel = HealthViewModel(context, HealthRepository.get(context))

    private fun render(config: HealthMetricConfig) {
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.BarChartDetails(config)))
        rule.setContent { BarChartDetails(backStack, vm(), config) }
        rule.waitForIdle()
    }

    @Test
    fun `steps details renders and switches tabs`() {
        render(HealthMetricConfig.STEPS)
        rule.onNodeWithText("Week").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Month").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Year").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Day").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Week").assertExists()
    }

    @Test
    fun `heart rate details renders line chart`() {
        render(HealthMetricConfig.HEART_RATE)
    }

    @Test
    fun `blood pressure details renders dual series`() {
        render(HealthMetricConfig.BLOOD_PRESSURE)
    }

    @Test
    fun `weight and sleep related details render`() {
        render(HealthMetricConfig.WEIGHT)
    }

    @Test
    fun `hydration details renders`() {
        render(HealthMetricConfig.HYDRATION)
    }
}
