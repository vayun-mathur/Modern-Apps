package com.vayunmathur.health.util

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/** Sparse history rows must be labelled by their bucket date, not position. */
class HistoryBucketLabelTest {
    @Test
    fun bucketKeyResolvesToItsOwnDate() {
        // Only Mar 15 and Jun 1 have data: position-based labels would call
        // them Mar 1 / Mar 2; bucket keys keep their real dates.
        assertEquals(
            LocalDate(2026, 3, 15),
            HealthChartData.bucketDate(LocalDate(2026, 3, 15).toEpochDays().toLong()),
        )
        assertEquals(
            LocalDate(2026, 6, 1),
            HealthChartData.bucketDate(LocalDate(2026, 6, 1).toEpochDays().toLong()),
        )
    }
}
