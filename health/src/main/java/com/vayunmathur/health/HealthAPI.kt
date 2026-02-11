package com.vayunmathur.health

import android.content.Context
import android.content.SharedPreferences
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.feature.ExperimentalPersonalHealthRecordApi
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.MedicalResource
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadMedicalResourcesInitialRequest
import androidx.health.connect.client.request.ReadMedicalResourcesPageRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Period
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object HealthAPI {
    lateinit var healthConnectClient: HealthConnectClient
    lateinit var preferences: SharedPreferences

    fun init(healthConnectClient: HealthConnectClient, context: Context) {
        this.healthConnectClient = healthConnectClient
        preferences = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    }

    suspend fun aggregates(timeRangeFilter: TimeRangeFilter, vararg metrics: AggregateMetric<*>): AggregationResult {
        return healthConnectClient.aggregate(
            AggregateRequest(metrics.toSet(), timeRangeFilter)
        )
    }

    suspend inline fun <reified T : Record> lastRecord(): T? {
        val records = healthConnectClient.readRecords(ReadRecordsRequest(T::class, TimeRangeFilter.before(
            Instant.now().plus(1, ChronoUnit.DAYS)), ascendingOrder = false, pageSize = 1))
        return records.records.lastOrNull()
    }

    fun timeRangeToday(anchor: LocalDate = LocalDate.now()): TimeRangeFilter {
        val startOfDay = anchor.atStartOfDay()
        val endOfDay = anchor.atTime(LocalTime.MAX)
        return TimeRangeFilter.between(startOfDay, endOfDay)
    }

    fun timeRangeThisWeek(anchor: LocalDate = LocalDate.now()): TimeRangeFilter {
        val startOfWeek = anchor.with(java.time.DayOfWeek.MONDAY).atStartOfDay()
        val endOfWeek = startOfWeek.plusDays(6).toLocalDate().atTime(LocalTime.MAX)
        return TimeRangeFilter.between(startOfWeek, endOfWeek)
    }

    fun timeRangeThisMonth(anchor: LocalDate = LocalDate.now()): TimeRangeFilter {
        val startOfMonth = anchor.with(TemporalAdjusters.firstDayOfMonth()).atStartOfDay()
        val endOfMonth = anchor.with(TemporalAdjusters.lastDayOfMonth()).atTime(LocalTime.MAX)
        return TimeRangeFilter.between(startOfMonth, endOfMonth)
    }

    fun timeRangeThisYear(anchor: LocalDate = LocalDate.now()): TimeRangeFilter {
        val startOfYear = anchor.with(TemporalAdjusters.firstDayOfYear()).atStartOfDay()
        val endOfYear = anchor.with(TemporalAdjusters.lastDayOfYear()).atTime(LocalTime.MAX)
        return TimeRangeFilter.between(startOfYear, endOfYear)
    }

    suspend fun aggregateByPeriod(
        timeRangeFilter: TimeRangeFilter,
        period: Period,
        metrics: Set<AggregateMetric<*>>
    ): List<AggregationResultGroupedByPeriod> {
        return healthConnectClient.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = metrics,
                timeRangeFilter = timeRangeFilter,
                timeRangeSlicer = period
            )
        )
    }

    suspend fun aggregateByDuration(
        timeRangeFilter: TimeRangeFilter,
        duration: Duration,
        metrics: Set<AggregateMetric<*>>
    ): List<AggregationResultGroupedByDuration> {
        return healthConnectClient.aggregateGroupByDuration(
            AggregateGroupByDurationRequest(
                metrics = metrics,
                timeRangeFilter = timeRangeFilter,
                timeRangeSlicer = duration
            )
        )
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