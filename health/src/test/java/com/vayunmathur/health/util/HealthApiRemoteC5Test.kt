@file:OptIn(androidx.health.connect.client.ExperimentalMatchmakingApi::class)

package com.vayunmathur.health.util
import com.vayunmathur.health.data.HealthAPI

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.Record as HcRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.library.util.databases
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [HealthAPI] with a real (fake) Health Connect client attached: writes
 * convert each supported Room record type into its HC record and insert
 * it; deletes map the type back to its HC class. Local-only mode is
 * covered by HealthApiLocalTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HealthApiRemoteC5Test {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val at: Instant = Instant.parse("2026-03-04T09:30:00Z")

    private class FakeClient : HealthConnectClient {
        val inserted = mutableListOf<HcRecord>()
        val deletedTypes = mutableListOf<KClass<out HcRecord>>()
        var failInsert = false

        override val permissionController: PermissionController
            get() = throw UnsupportedOperationException("not used")

        override suspend fun insertRecords(records: List<HcRecord>): InsertRecordsResponse {
            if (failInsert) throw IllegalStateException("insert exploded")
            inserted += records
            return InsertRecordsResponse(records.map { it.metadata.id })
        }

        override suspend fun deleteRecords(
            recordType: KClass<out HcRecord>,
            recordIdsList: List<String>,
            clientRecordIdsList: List<String>,
        ) {
            deletedTypes += recordType
        }

        override suspend fun updateRecords(records: List<HcRecord>) =
            throw UnsupportedOperationException("not used")

        override suspend fun deleteRecords(
            recordType: KClass<out HcRecord>,
            timeRangeFilter: TimeRangeFilter,
        ) = throw UnsupportedOperationException("not used")

        override suspend fun <T : HcRecord> readRecord(
            recordType: KClass<T>,
            recordId: String,
        ): ReadRecordResponse<T> = throw UnsupportedOperationException("not used")

        override suspend fun <T : HcRecord> readRecords(
            request: ReadRecordsRequest<T>,
        ): ReadRecordsResponse<T> = throw UnsupportedOperationException("not used")

        override suspend fun aggregate(request: AggregateRequest): AggregationResult =
            throw UnsupportedOperationException("not used")

        override suspend fun aggregateGroupByDuration(
            request: AggregateGroupByDurationRequest,
        ): List<AggregationResultGroupedByDuration> = throw UnsupportedOperationException("not used")

        override suspend fun aggregateGroupByPeriod(
            request: AggregateGroupByPeriodRequest,
        ): List<AggregationResultGroupedByPeriod> = throw UnsupportedOperationException("not used")

        override suspend fun getChangesToken(request: ChangesTokenRequest): String =
            throw UnsupportedOperationException("not used")

        override suspend fun getChanges(changesToken: String): ChangesResponse =
            throw UnsupportedOperationException("not used")

        override suspend fun getChanges(changesToken: String, maxChanges: Int): ChangesResponse =
            throw UnsupportedOperationException("not used")
    }

    private lateinit var client: FakeClient

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
        client = FakeClient()
        HealthAPI.init(client, context, HealthRepository.get(context))
    }

    private fun repository(): HealthRepository = HealthRepository.get(context)

    private fun record(type: RecordType, id: String, value: Double = 1.0) = Record(
        id = id, index = 0, type = type, startTime = at, endTime = at,
        value = value, metadata = "C5 $id",
        nutritionData = if (type == RecordType.Nutrition) {
            NutritionData(protein = 10.0, calories = 200.0)
        } else null,
    )

    @Test
    fun `writes convert and insert every supported type`() = runTest {
        val types = listOf(
            RecordType.Nutrition, RecordType.Hydration, RecordType.Weight,
            RecordType.Height, RecordType.BodyFat, RecordType.LeanBodyMass,
            RecordType.BoneMass, RecordType.BodyWaterMass,
        )
        // Values must satisfy Health Connect validation ranges.
        val values = mapOf(
            RecordType.Nutrition to 0.0, RecordType.Hydration to 0.5,
            RecordType.Weight to 70.0, RecordType.Height to 1.8,
            RecordType.BodyFat to 20.0, RecordType.LeanBodyMass to 50.0,
            RecordType.BoneMass to 3.0, RecordType.BodyWaterMass to 40.0,
        )
        types.forEachIndexed { i, type ->
            HealthAPI.writeHealthRecord(record(type, "remote-write-$i", values.getValue(type)))
        }
        assertEquals(types.size, client.inserted.size)
        assertEquals(
            types.map { it.name }.sorted(),
            client.inserted.map { it.javaClass.simpleName.removeSuffix("Record") }.sorted()
                .map { name ->
                    // HC class names differ slightly from RecordType names.
                    when (name) {
                        "Nutrition" -> "Nutrition"
                        "Hydration" -> "Hydration"
                        "Weight" -> "Weight"
                        "Height" -> "Height"
                        "BodyFat" -> "BodyFat"
                        "LeanBodyMass" -> "LeanBodyMass"
                        "BoneMass" -> "BoneMass"
                        "BodyWaterMass" -> "BodyWaterMass"
                        else -> name
                    }
                },
        )
        // Unsupported types are a no-op, never an insert.
        HealthAPI.writeHealthRecord(record(RecordType.Steps, "remote-steps", 100.0))
        assertEquals(types.size, client.inserted.size)
    }

    @Test
    fun `insert failure is swallowed after the local write`() = runTest {
        client.failInsert = true
        // Must not throw: the caller already wrote Room.
        HealthAPI.writeHealthRecord(record(RecordType.Weight, "remote-fail", 70.0))
        assertTrue(client.inserted.isEmpty())
    }

    @Test
    fun `deletes remove locally and map types remotely`() = runTest {
        val repo = repository()
        val weight = record(RecordType.Weight, "remote-del-weight", 70.0)
        repo.upsert(listOf(weight))
        assertTrue(repo.getRecordsFlow(RecordType.Weight).first().any { it.id == weight.id })
        HealthAPI.deleteRecord(weight)
        assertTrue(repo.getRecordsFlow(RecordType.Weight).first().none { it.id == weight.id })
        assertEquals(
            listOf("WeightRecord"),
            client.deletedTypes.map { it.simpleName },
        )

        // A type with no HC class deletes locally only.
        val steps = record(RecordType.Steps, "remote-del-steps", 10.0)
        repo.upsert(listOf(steps))
        HealthAPI.deleteRecord(steps)
        assertEquals(1, client.deletedTypes.size)
        assertTrue(repo.getRecordsFlow(RecordType.Steps).first().none { it.id == steps.id })
    }
}
