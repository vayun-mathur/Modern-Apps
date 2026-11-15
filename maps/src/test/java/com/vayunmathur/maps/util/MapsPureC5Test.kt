package com.vayunmathur.maps.util

import java.util.Locale
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C5 pure-logic branches: regional distance formatting, category term
 * expansion, and the search hit table's growth/rehash invariants.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsPureC5Test {

    @Test
    fun `distances format in metric and imperial locales`() {
        val metric = formatDistance(800.0, Locale.GERMANY)
        assertTrue(metric.contains("800"), "metric metres: $metric")
        val metricKm = formatDistance(2_300.0, Locale.GERMANY)
        assertTrue(metricKm.contains("2,3") || metricKm.contains("2.3"), "metric km: $metricKm")
        val imperial = formatDistance(1_609.0, Locale.US)
        assertTrue(imperial.contains("mi"), "imperial mile: $imperial")
        val feet = formatDistance(30.0, Locale.US)
        assertTrue(feet.contains("ft") || feet.contains("98"), "imperial feet: $feet")
        assertTrue(formatDistance(0.0, Locale.GERMANY).contains("0"))
    }

    @Test
    fun `duration parts truncate and clamp`() {
        assertEquals(DurationParts(0, 0), durationParts(59.toDuration(DurationUnit.SECONDS)))
        assertEquals(DurationParts(1, 12), durationParts((72 * 60 + 40).toDuration(DurationUnit.SECONDS)))
        assertEquals(DurationParts(0, 0), durationParts((-5).toDuration(DurationUnit.MINUTES)))
        assertTrue(durationParts(30.toDuration(DurationUnit.SECONDS)).isUnderAMinute)
    }

    @Test
    fun `category terms expand from labels and synonyms`() {
        assertTrue(PoiCategories.typesForTerm("coffee").contains(1))
        assertTrue(PoiCategories.typesForTerm("gas").contains(6))
        assertTrue(PoiCategories.typesForTerm("pharmacy").isNotEmpty())
        assertTrue(PoiCategories.typesForTerm("parking").contains(16))
        assertTrue(PoiCategories.typesForTerm("zzzznotacategory").isEmpty())
        // Labels are matched on singularised words.
        assertTrue(PoiCategories.typesForTerm("restaurant").isNotEmpty())
    }

    @Test
    fun `hit table rehashes and keeps dense indices stable`() {
        val table = HitTable(tokenCount = 2, expected = 4)
        val dense = HashMap<Int, Int>()
        // Far more inserts than the initial capacity forces several rehashes.
        for (ordinal in 0 until 500) {
            val idx = table.add(ordinal)
            dense[ordinal] = idx
            assertEquals(idx, table.indexOf(ordinal))
        }
        assertEquals(500, table.size)
        // Re-adding an existing ordinal returns its dense index, no growth.
        assertEquals(dense.getValue(250), table.add(250))
        assertEquals(500, table.size)
        assertEquals(-1, table.indexOf(10_000))
        // Marks land per (entry, token) and do not leak across tokens.
        val idx = table.add(777)
        table.mark(idx, token = 1, wordIdx = 3)
        assertEquals(1L shl 3, table.mask(idx, 1))
        assertEquals(0L, table.mask(idx, 0))
        table.mark(idx, token = 1, wordIdx = 70) // clamps to the shared top bit
        assertTrue(table.mask(idx, 1) and (1L shl 63) != 0L)
    }
}
