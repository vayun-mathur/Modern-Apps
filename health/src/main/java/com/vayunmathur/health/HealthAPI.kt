package com.vayunmathur.health

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi
import androidx.health.connect.client.records.MedicalResource
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadMedicalResourcesInitialRequest
import androidx.health.connect.client.request.ReadMedicalResourcesPageRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.vayunmathur.health.database.HealthDatabase
import com.vayunmathur.health.database.Record
import com.vayunmathur.health.database.RecordType
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

object HealthAPI {
    lateinit var healthConnectClient: HealthConnectClient
    lateinit var db: HealthDatabase
    lateinit var preferences: SharedPreferences

    fun init(healthConnectClient: HealthConnectClient, context: Context, db: HealthDatabase) {
        this.healthConnectClient = healthConnectClient
        this.db = db
        preferences = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    }

    @Composable
    fun sumInRange(recordType: RecordType, startTime: Instant, endTime: Instant): Flow<Double> {
        return remember { db.healthDao().sumInRange(recordType, startTime, endTime) }
    }

    @Composable
    fun maxInRange(recordType: RecordType, startTime: Instant, endTime: Instant): Flow<Double> {
        return remember { db.healthDao().maxInRange(recordType, startTime, endTime) }
    }

    @Composable
    fun minInRange(recordType: RecordType, startTime: Instant, endTime: Instant): Flow<Double> {
        return remember { db.healthDao().minInRange(recordType, startTime, endTime) }
    }

    suspend inline fun lastRecord(recordType: RecordType): Record? {
        return db.healthDao().getLastRecord(recordType)
    }

    enum class PeriodType {
        Hourly, Daily, Weekly
    }

    suspend fun getListOfSums(
        recordType: RecordType,
        startTime: Instant,
        endTime: Instant,
        period: PeriodType
    ): List<Double> {
        val tz = TimeZone.currentSystemDefault()
        val sums = mutableListOf<Double>()

        var currentStart = startTime

        while (currentStart < endTime) {
            val nextStart = when (period) {
                PeriodType.Hourly -> {
                    currentStart.plus(1.hours)
                }
                PeriodType.Daily -> {
                    // Shift to local time, add a day, shift back to Instant
                    val localDateTime = currentStart.toLocalDateTime(tz)
                    val nextLocalDate = localDateTime.date.plus(1, DateTimeUnit.DAY)
                    nextLocalDate.atTime(localDateTime.hour, localDateTime.minute).toInstant(tz)
                }
                PeriodType.Weekly -> {
                    val localDateTime = currentStart.toLocalDateTime(tz)
                    val nextLocalDate = localDateTime.date.plus(1, DateTimeUnit.WEEK)
                    nextLocalDate.atTime(localDateTime.hour, localDateTime.minute).toInstant(tz)
                }
            }

            // Clamp the end range to the requested endTime
            val currentEnd = if (nextStart > endTime) endTime else nextStart

            // Fetch sum from DAO
            val sum = db.healthDao().sumInRangeGet(recordType, currentStart, currentEnd)
            sums.add(sum)

            currentStart = nextStart
        }

        return sums
    }

    @OptIn(ExperimentalPersonalHealthRecordApi::class)
    suspend fun allMedicalRecords(type: Int): List<MedicalResource> {
        val allRecords = mutableListOf<MedicalResource>()
        var pageToken: String? = null
        do {
            val request = if (pageToken == null) ReadMedicalResourcesInitialRequest(type, setOf())
            else ReadMedicalResourcesPageRequest(pageToken)
            val response = healthConnectClient.readMedicalResources(request)
            allRecords += response.medicalResources
            pageToken = response.nextPageToken
        } while (pageToken != null)
        return allRecords
    }
}