package com.vayunmathur.health.util

import kotlin.test.Test
import kotlin.test.assertEquals

/** Weekly/monthly buckets: sums must sum daily sums, averages must average. */
class AggregateCombineTest {
    @Test
    fun sumsCombineBySumming() {
        assertEquals(21000.0, HealthAPI.combineForSums(listOf(5000.0, 8000.0, 8000.0)))
        assertEquals(0.0, HealthAPI.combineForSums(emptyList()))
    }

    @Test
    fun averagesCombineByAveraging() {
        assertEquals(7000.0, HealthAPI.combineForAverages(listOf(5000.0, 8000.0, 8000.0)))
    }
}
