package com.vayunmathur.health.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate

/**
 * The chart range arithmetic behind every metric dashboard.
 *
 * A wrong range silently charts the wrong week or month; the week-start bug
 * (#727) already proved this file deserves pinning. `resolveChartRange` is
 * pure JVM — no repository, no resources.
 */
class HealthChartRangeTest {

    // 2026-09-16 is a Wednesday; its Sunday-started week opens 2026-09-13.
    private val wednesday = LocalDate(2026, 9, 16)
    private val sunday = LocalDate(2026, 9, 13)
    private val saturday = LocalDate(2026, 9, 12)

    @Test
    fun `day tab covers exactly the anchor day`() {
        val range = HealthChartData.resolveChartRange(wednesday, 0)
        assertEquals(wednesday, range.first)
        assertEquals(LocalDate(2026, 9, 17), range.second)
        assertEquals(HealthAPI.PeriodType.Hourly, range.third)
        assertEquals(HealthAPI.PeriodType.Hourly, range.fourth)
    }

    @Test
    fun `week tab always opens on the preceding Sunday`() {
        val range = HealthChartData.resolveChartRange(wednesday, 1)
        assertEquals(sunday, range.first)
        assertEquals(LocalDate(2026, 9, 20), range.second)
        assertEquals(HealthAPI.PeriodType.Daily, range.third)
        assertEquals(HealthAPI.PeriodType.Daily, range.fourth)
    }

    @Test
    fun `week tab on a Sunday starts that same day`() {
        val range = HealthChartData.resolveChartRange(sunday, 1)
        assertEquals(sunday, range.first)
        assertEquals(LocalDate(2026, 9, 20), range.second)
    }

    @Test
    fun `week tab on a Saturday still reaches back to Sunday`() {
        val range = HealthChartData.resolveChartRange(saturday, 1)
        assertEquals(LocalDate(2026, 9, 6), range.first)
        assertEquals(LocalDate(2026, 9, 13), range.second)
    }

    @Test
    fun `month tab covers the calendar month`() {
        val range = HealthChartData.resolveChartRange(wednesday, 2)
        assertEquals(LocalDate(2026, 9, 1), range.first)
        assertEquals(LocalDate(2026, 10, 1), range.second)
        assertEquals(HealthAPI.PeriodType.Daily, range.third)
        assertEquals(HealthAPI.PeriodType.Weekly, range.fourth)
    }

    @Test
    fun `year tab covers the calendar year`() {
        val range = HealthChartData.resolveChartRange(wednesday, 3)
        assertEquals(LocalDate(2026, 1, 1), range.first)
        assertEquals(LocalDate(2027, 1, 1), range.second)
        assertEquals(HealthAPI.PeriodType.Monthly, range.third)
        assertEquals(HealthAPI.PeriodType.Monthly, range.fourth)
    }

    @Test
    fun `decoding is the inverse of encoding for exact values`() {
        // (mantissa << 4) | exponent packed by the generator; spot-check the
        // documented format through crafted blobs.
        fun blob(slotsAndValues: List<Pair<Int, Long>>): ByteArray {
            var present = 0L
            for ((slot, _) in slotsAndValues) present = present or (1L shl slot)
            val out = mutableListOf<Byte>()
            for (i in 0 until 6) out += (present shr (8 * i)).toByte()
            for ((_, packed) in slotsAndValues) {
                var v = packed
                do {
                    var b = (v and 0x7F).toByte()
                    v = v ushr 7
                    if (v != 0L) b = (b.toInt() or 0x80).toByte()
                    out += b
                } while (v != 0L)
            }
            return out.toByteArray()
        }

        // protein slot 0: 25.5 = (255 << 4) | 1.
        val decoded = FoodDatabase.decodeNutrients(blob(listOf(0 to ((255L shl 4) or 1))))
        assertEquals(25.5, decoded.protein, 1e-9)
        // calories slot 40: 900 = (900 << 4) | 0.
        val kcal = FoodDatabase.decodeNutrients(blob(listOf(40 to ((900L shl 4) or 0))))
        assertEquals(900.0, kcal.calories, 1e-9)
        // Unset slots read as zero.
        assertEquals(0.0, decoded.calories)
    }

    @Test
    fun `truncated and empty blobs degrade to zeros`() {
        assertEquals(0.0, FoodDatabase.decodeNutrients(ByteArray(0)).protein)
        assertEquals(0.0, FoodDatabase.decodeNutrients(ByteArray(3)).calories)
        // Bitmap claims a value but the blob ends: no throw, reads zero.
        val header = ByteArray(6)
        header[0] = 0x01
        assertEquals(0.0, FoodDatabase.decodeNutrients(header).protein)
    }

    @Test
    fun `food fts escaping quotes every token without prefix stars`() {
        assertEquals("\"cheddar\" \"cheese\"", FoodDatabase.escapeFtsQuery("cheddar cheese"))
        assertNull(FoodDatabase.escapeFtsQuery("?!"))
    }

    @Test
    fun `food database status carries its metadata`() {
        val meta = FoodDatabase.Meta(schemaVersion = 6, rows = 100)
        assertNull(FoodDatabase.Status.Absent.installed)
        assertEquals(meta, FoodDatabase.Status.Ready(meta).installed)
        val preparing = FoodDatabase.Status.Preparing(10, 100)
        assertEquals(10L, preparing.productsWritten)
        assertNull(preparing.installed)
    }
}
