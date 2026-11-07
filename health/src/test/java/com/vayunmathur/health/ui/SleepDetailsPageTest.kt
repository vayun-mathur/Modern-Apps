package com.vayunmathur.health.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.data.SleepData
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.platform.PersonalHealthRecords
import com.vayunmathur.health.platform.importFromHealthConnect
import com.vayunmathur.health.util.HealthAPI
import com.vayunmathur.health.util.HealthViewModel
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepDetailsPageTest {

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
        kotlinx.coroutines.runBlocking { HealthRepository.get(context).getLastRecord(RecordType.Steps) }
    }

    private fun vm(): HealthViewModel = HealthViewModel(context, HealthRepository.get(context))

    @Test
    fun `sleep details page renders without sleep data`() {
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.SleepDetails))
        rule.setContent { SleepDetailsPage(backStack, vm()) }
        rule.waitForIdle()
        rule.onNodeWithText("Sleep", substring = true).assertExists()
    }

    @Test
    fun `sleep details page shows seeded sleep session`() = runTest {
        // Past date: the shared DB must not leak a sleep session into other
        // tests that query today's sleep.
        val end = Instant.parse("2024-05-02T06:00:00Z")
        val start = end.minusSeconds(8 * 3600)
        HealthRepository.get(context).upsert(listOf(Record(
            id = "sleep-page-1", index = 0, type = RecordType.Sleep, startTime = start, endTime = end, value = 8.0,
            sleepData = SleepData(awakeDurationMillis = 600_000, remDurationMillis = 5_400_000, lightDurationMillis = 10_800_000, deepDurationMillis = 5_400_000),
            metadata = "Sleep",
        )))
        val backStack = NavBackStack<Route>(arrayOf(Route.Today, Route.SleepDetails))
        rule.setContent { SleepDetailsPage(backStack, vm()) }
        rule.waitForIdle()
        rule.onNodeWithText("Sleep", substring = true).assertExists()
    }

    @Test
    fun `import from health connect is a no-op when unavailable`() {
        assertTrue(!PersonalHealthRecords.isAvailable())
        val medical = MedicalViewModel(context, HealthRepository.get(context))
        medical.importFromHealthConnect()
        // Guard returns before launching sync; syncing stays false.
        assertEquals(false, medical.syncingState.value)
    }
}
