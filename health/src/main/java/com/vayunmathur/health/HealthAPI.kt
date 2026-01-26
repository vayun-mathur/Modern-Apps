package com.vayunmathur.health

import android.content.Context
import android.content.SharedPreferences
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.MedicalResource
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadMedicalResourcesInitialRequest
import androidx.health.connect.client.request.ReadMedicalResourcesPageRequest
import androidx.health.connect.client.request.ReadMedicalResourcesRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadMedicalResourcesResponse
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaInstant

object HealthAPI {
    lateinit var healthConnectClient: HealthConnectClient
    lateinit var preferences: SharedPreferences

    private val recordTypes = setOf(HeartRateRecord::class, SleepSessionRecord::class)

    fun init(healthConnectClient: HealthConnectClient, context: Context) {
        this.healthConnectClient = healthConnectClient
        preferences = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    }

    suspend fun aggregates(timeRangeFilter: TimeRangeFilter, vararg metrics: AggregateMetric<*>): AggregationResult {
        return healthConnectClient.aggregate(
            AggregateRequest(metrics.toSet(), timeRangeFilter)
        )
    }

    suspend inline fun <reified T: Record> lastRecord(): T? {
        val records = healthConnectClient.readRecords(ReadRecordsRequest(T::class, timeRangeThisWeek()))
        return records.records.lastOrNull()
    }

    fun timeRangeToday(): TimeRangeFilter {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return TimeRangeFilter.between(
            now.date.atStartOfDayIn(TimeZone.currentSystemDefault()).toJavaInstant(),
            now.toInstant(TimeZone.currentSystemDefault()).toJavaInstant()
        )
    }

    fun timeRangeThisWeek(): TimeRangeFilter {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return TimeRangeFilter.between(
            (now.date - DatePeriod(days = 3)).atStartOfDayIn(TimeZone.currentSystemDefault()).toJavaInstant(),
            now.toInstant(TimeZone.currentSystemDefault()).toJavaInstant()
        )
    }

    @OptIn(ExperimentalPersonalHealthRecordApi::class)
    suspend fun allMedicalRecords(type: Int): List<MedicalResource> {
        val allRecords = mutableListOf<MedicalResource>()

        var pageToken: String? = null
        do {
            val request =
                if (pageToken == null) ReadMedicalResourcesInitialRequest(type, setOf())
                else ReadMedicalResourcesPageRequest(pageToken)

            val response = healthConnectClient.readMedicalResources(request)
            allRecords += response.medicalResources
            pageToken = response.nextPageToken
        } while (pageToken != null)

        return allRecords
    }
}