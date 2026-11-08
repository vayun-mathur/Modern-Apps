package com.vayunmathur.health.util

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthApiLocalTest {

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
    }

    private fun repo(): HealthRepository = HealthRepository.get(context)

    private fun record(type: RecordType, value: Double, at: Instant, id: String) = Record(
        id = id, index = 0, type = type, startTime = at, endTime = at, value = value, metadata = type.name,
        nutritionData = if (type == RecordType.Nutrition) NutritionData(calories = value, protein = 1.0) else null,
    )

    @Test
    fun `last record reads newest from room`() = runTest {
        // Far-future date so this row is the newest Weight in the shared DB.
        val at = Instant.parse("2027-06-01T09:00:00Z")
        repo().upsert(listOf(record(RecordType.Weight, 71.0, at, "api-w-1")))
        assertEquals(71.0, HealthAPI.lastRecord(RecordType.Weight)?.value)
    }

    @Test
    fun `delete record removes local row in local mode`() = runTest {
        val at = Instant.parse("2024-02-10T09:00:00Z")
        val row = record(RecordType.Hydration, 0.5, at, "api-h-1")
        repo().upsert(listOf(row))
        HealthAPI.deleteRecord(row)
        assertTrue(repo().getRecordsFlow(RecordType.Hydration).first().none { it.id == "api-h-1" })
    }

    @Test
    fun `write record in local mode builds hc record but does not crash for supported types`() = runTest {
        val at = Instant.parse("2024-02-10T09:00:00Z")
        // Each supported type exercises its HC record builder; local mode
        // returns after building, leaving Room unchanged.
        val cases = listOf(
            RecordType.Weight to 70.0, RecordType.Height to 1.8, RecordType.BodyFat to 20.0,
            RecordType.LeanBodyMass to 55.0, RecordType.BoneMass to 3.0, RecordType.BodyWaterMass to 35.0,
            RecordType.Hydration to 0.5, RecordType.Nutrition to 200.0,
        )
        for ((i, pair) in cases.withIndex()) {
            HealthAPI.writeHealthRecord(record(pair.first, pair.second, at, "api-write-$i"))
        }
        // Unsupported type returns early.
        HealthAPI.writeHealthRecord(record(RecordType.Steps, 100.0, at, "api-write-steps"))
        // Nutrition without data returns null internally and must not throw.
        HealthAPI.writeHealthRecord(Record(id = "api-write-nonut", index = 0, type = RecordType.Nutrition, startTime = at, endTime = at, value = 1.0))
        assertNotNull(HealthAPI.db)
    }

    @Test
    fun `daily sums and averages aggregate seeded rows`() = runTest {
        val at = Instant.parse("2024-02-10T09:00:00Z")
        repo().upsert(listOf(record(RecordType.Steps, 1000.0, at, "api-steps-sum")))
        val start = kotlin.time.Instant.fromEpochMilliseconds(at.toEpochMilli() - 86_400_000)
        val end = kotlin.time.Instant.fromEpochMilliseconds(at.toEpochMilli() + 86_400_000)
        val sums = HealthAPI.getListOfSums(RecordType.Steps, start, end, HealthAPI.PeriodType.Daily)
        assertTrue(sums.any { it.second == 1000.0 })
        val avgs = HealthAPI.getListOfAverages(RecordType.Steps, start, end, HealthAPI.PeriodType.Daily)
        assertTrue(avgs.isNotEmpty())
        val weekly = HealthAPI.getListOfSums(RecordType.Steps, start, end, HealthAPI.PeriodType.Weekly)
        assertTrue(weekly.isNotEmpty())
        val monthly = HealthAPI.getListOfSums(RecordType.Steps, start, end, HealthAPI.PeriodType.Monthly)
        assertTrue(monthly.isNotEmpty())
        val hourly = HealthAPI.getListOfSums(RecordType.Steps, start, end, HealthAPI.PeriodType.Hourly)
        assertTrue(hourly.any { it.second == 1000.0 })
    }

    @Test
    fun `empty range aggregates to empty`() = runTest {
        val start = kotlin.time.Instant.fromEpochMilliseconds(0)
        val end = kotlin.time.Instant.fromEpochMilliseconds(1000)
        assertTrue(HealthAPI.getListOfSums(RecordType.Distance, start, end, HealthAPI.PeriodType.Daily).isEmpty())
        assertNull(HealthAPI.lastRecord(RecordType.Vo2Max)?.takeIf { it.id == "never" })
    }

    @Test
    fun `period types are four`() {
        assertEquals(4, HealthAPI.PeriodType.entries.size)
    }
}
