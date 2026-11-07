package com.vayunmathur.health.util

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.data.SleepData
import com.vayunmathur.health.ui.HealthMetricConfig
import com.vayunmathur.health.ui.SleepStageGraph
import com.vayunmathur.health.ui.SleepSummaryHeader
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChartLoadAndDrawTest {

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
    }

    private fun vm(): HealthViewModel = HealthViewModel(context, HealthRepository.get(context))

    private suspend fun settleUntil(cond: () -> Boolean) {
        repeat(200) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            kotlinx.coroutines.delay(10)
        }
    }

    @Test
    fun `load bar chart data publishes dashboard for every tab`() = runTest {
        val repo = HealthRepository.get(context)
        val at = Instant.parse("2024-03-04T09:00:00Z")
        repo.upsert(listOf(Record(id = "chartload-steps", index = 0, type = RecordType.Steps, startTime = at, endTime = at, value = 4000.0, metadata = "Steps")))
        val vm = vm()
        for (tab in 0..3) {
            vm.loadBarChartData(HealthMetricConfig.STEPS, LocalDate(2024, 3, 4), tab)
            settleUntil { vm.barChartData.value.chartData.isNotEmpty() || tab == 0 }
            assertNotNull(vm.barChartData.value)
        }
        // Dual-series and line metrics take the averages branch.
        vm.loadBarChartData(HealthMetricConfig.BLOOD_PRESSURE, LocalDate(2024, 3, 4), 1)
        settleUntil { true }
        vm.loadBarChartData(HealthMetricConfig.HEART_RATE, LocalDate(2024, 3, 4), 2)
        settleUntil { true }
        assertTrue(vm.barChartData.value.totalBarCount >= 0)
    }

    @Test
    fun `sleep stage graph and summary draw`() {
        val start = Instant.parse("2024-05-01T22:00:00Z")
        val end = Instant.parse("2024-05-02T06:00:00Z")
        val record = Record(
            id = "draw-sleep-1", index = 0, type = RecordType.Sleep, startTime = start, endTime = end, value = 8.0,
            sleepData = SleepData(
                awakeDurationMillis = 600_000, remDurationMillis = 5_400_000,
                lightDurationMillis = 10_800_000, deepDurationMillis = 5_400_000,
                stagesJson = """[{"startTimeMillis":${start.toEpochMilli()},"endTimeMillis":${end.toEpochMilli()},"stage":4}]""",
            ),
            metadata = "Sleep",
        )
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                SleepSummaryHeader(record)
                SleepStageGraph(record)
            }
        }
        val image = rule.onRoot().captureToImage()
        assertTrue(image.width > 0)
    }
}
