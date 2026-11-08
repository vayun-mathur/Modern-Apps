package com.vayunmathur.health.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/**
 * The Room column converters and the small entity helpers in [Record.kt].
 *
 * These run on every read and write, so a wrong mapping corrupts the whole
 * store silently. Pure JVM: no database needed.
 */
class ConvertersTest {

    private val converters = Converters()

    @Test
    fun `timestamps round trip`() {
        val instant = java.time.Instant.parse("2026-03-04T09:30:00Z")
        assertEquals(instant, converters.fromTimestamp(converters.dateToTimestamp(instant)))
        assertNull(converters.fromTimestamp(null))
        assertNull(converters.dateToTimestamp(null))
    }

    @Test
    fun `kotlin instants round trip through epoch millis`() {
        val instant = kotlin.time.Instant.fromEpochMilliseconds(1_771_234_567_890L)
        assertEquals(instant, converters.fromTS(converters.toTS(instant)))
    }

    @Test
    fun `dose times survive the comma separated column`() {
        val times = listOf(8 * 3600, 20 * 3600)
        assertEquals("28800,72000", converters.fromIntList(times))
        assertEquals(times, converters.toIntList("28800,72000"))
    }

    @Test
    fun `blank and padded dose time lists parse leniently`() {
        assertEquals(emptyList(), converters.toIntList(""))
        assertEquals(emptyList(), converters.toIntList("   "))
        assertEquals(listOf(3600), converters.toIntList(" 3600 "))
        // A corrupt token is skipped rather than failing the whole schedule read.
        assertEquals(listOf(3600), converters.toIntList("3600,abc"))
    }

    @Test
    fun `local dates round trip through epoch days`() {
        val date = LocalDate(2026, 3, 4)
        assertEquals(date, converters.toLocalDate(converters.fromLocalDate(date)))
        assertEquals(date, converters.toLocalDateNullable(converters.fromLocalDateNullable(date)))
        assertNull(converters.toLocalDateNullable(converters.fromLocalDateNullable(null)))
    }

    @Test
    fun `ingredient display prefers the custom name`() {
        val ingredient = Ingredient(
            id = "i1",
            originalName = "Whole milk",
            nutritionData = NutritionData(),
        )
        assertEquals("Whole milk", ingredient.displayName)
        assertEquals(
            "Skimmed",
            ingredient.copy(customName = "Skimmed").displayName,
        )
    }

    @Test
    fun `record types cover the dashboard surface`() {
        // Every type the UI can chart must exist; a missing entry is a crash
        // in the metric picker rather than a gap in coverage.
        for (name in listOf("Steps", "Sleep", "Nutrition", "Hydration", "Exercise", "BloodPressure")) {
            assertTrue(RecordType.entries.any { it.name == name }, "RecordType.$name missing")
        }
    }

    @Test
    fun `record primary key defaults to id plus index`() {
        val record = Record(
            id = "r1",
            index = 2,
            type = RecordType.Steps,
            startTime = java.time.Instant.EPOCH,
            endTime = java.time.Instant.EPOCH,
            value = 10.0,
        )
        assertEquals("r1-2", record.primaryKey)
    }

    @Test
    fun `nutrition data defaults to zero`() {
        val empty = NutritionData()
        assertEquals(0.0, empty.protein)
        assertEquals(0.0, empty.calories)
        assertEquals(0.0, empty.vitaminD)
    }

    @Test
    fun `sleep and exercise payloads default to empty`() {
        val sleep = SleepData()
        assertEquals(0L, sleep.awakeDurationMillis)
        assertNull(sleep.stagesJson)
        val exercise = ExerciseData()
        assertEquals(0, exercise.exerciseType)
        assertNull(exercise.title)
    }
}
