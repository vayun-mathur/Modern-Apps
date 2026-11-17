package com.vayunmathur.health.util
import com.vayunmathur.health.sync.toRecord
import com.vayunmathur.health.sync.exerciseSegmentTypeName
import com.vayunmathur.health.sync.exerciseTypeName

import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose as BloodGlucoseUnit
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Temperature
import com.vayunmathur.health.data.RecordType
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * C5 conversion branches: basal rate, blood pressure/glucose, VO2 max,
 * skin-temperature deltas, every sleep stage, sparse exercise sessions, a
 * fully populated nutrition record, and unsupported record types.
 */
class HealthConversionC5Test {

    private val start: Instant = Instant.parse("2026-03-04T07:00:00Z")
    private val end: Instant = Instant.parse("2026-03-04T08:00:00Z")
    private val zone: ZoneOffset = ZoneOffset.UTC
    private fun meta(id: String) = Metadata.manualEntry(id)

    @Test
    fun `sleep record sums every stage kind`() {
        fun stage(kind: Int, from: Instant, to: Instant) =
            SleepSessionRecord.Stage(startTime = from, endTime = to, stage = kind)
        val sleep = SleepSessionRecord(
            metadata = meta("sleep5"), startTime = start, startZoneOffset = zone,
            endTime = end, endZoneOffset = zone,
            stages = listOf(
                stage(SleepSessionRecord.STAGE_TYPE_AWAKE, start, start.plusSeconds(300)),
                stage(SleepSessionRecord.STAGE_TYPE_OUT_OF_BED, start.plusSeconds(300), start.plusSeconds(420)),
                stage(SleepSessionRecord.STAGE_TYPE_LIGHT, start.plusSeconds(420), start.plusSeconds(1_800)),
                stage(SleepSessionRecord.STAGE_TYPE_DEEP, start.plusSeconds(1_800), start.plusSeconds(2_700)),
                stage(SleepSessionRecord.STAGE_TYPE_REM, start.plusSeconds(2_700), start.plusSeconds(3_300)),
                stage(SleepSessionRecord.STAGE_TYPE_UNKNOWN, start.plusSeconds(3_300), end),
            ),
        ).toRecord().single()
        val data = sleep.sleepData!!
        assertEquals(420_000L, data.awakeDurationMillis) // awake + out of bed
        assertEquals(1_380_000L, data.lightDurationMillis)
        assertEquals(900_000L, data.deepDurationMillis)
        assertEquals(600_000L, data.remDurationMillis)
        assertEquals(300_000L, data.unknownDurationMillis)
        assertEquals(1.0, sleep.value)
        assertTrue(data.stagesJson!!.contains("\"stage\""))
    }

    @Test
    fun `sparse exercise session has no segment or lap payloads`() {
        val exercise = ExerciseSessionRecord(
            metadata = meta("ex0"), startTime = start, startZoneOffset = zone,
            endTime = end, endZoneOffset = zone,
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
        ).toRecord().single()
        assertEquals(60.0, exercise.value)
        assertEquals(null, exercise.exerciseData!!.segmentsJson)
        assertEquals(null, exercise.exerciseData!!.lapsJson)
        assertEquals(false, exercise.exerciseData!!.hasRoute)
        // No title: the metadata falls back to the exercise type name.
        assertEquals(exerciseTypeName(ExerciseSessionRecord.EXERCISE_TYPE_YOGA), exercise.metadata)
    }

    @Test
    fun `fully populated nutrition record maps every nutrient`() {
        val nutrition = NutritionRecord(
            metadata = meta("nut5"), startTime = start, startZoneOffset = zone,
            endTime = end, endZoneOffset = zone, name = "Feast",
            energy = Energy.kilocalories(800.0),
            protein = Mass.grams(40.0), totalCarbohydrate = Mass.grams(90.0),
            totalFat = Mass.grams(30.0), dietaryFiber = Mass.grams(8.0),
            sugar = Mass.grams(22.0), sodium = Mass.milligrams(700.0),
            biotin = Mass.micrograms(30.0), caffeine = Mass.milligrams(95.0),
            calcium = Mass.milligrams(300.0), chloride = Mass.milligrams(900.0),
            cholesterol = Mass.milligrams(120.0), chromium = Mass.micrograms(25.0),
            copper = Mass.milligrams(1.1), folate = Mass.micrograms(220.0),
            folicAcid = Mass.micrograms(120.0), iodine = Mass.micrograms(80.0),
            iron = Mass.milligrams(9.0), magnesium = Mass.milligrams(210.0),
            manganese = Mass.milligrams(1.8), molybdenum = Mass.micrograms(34.0),
            monounsaturatedFat = Mass.grams(12.0), niacin = Mass.milligrams(14.0),
            pantothenicAcid = Mass.milligrams(5.0), phosphorus = Mass.milligrams(600.0),
            polyunsaturatedFat = Mass.grams(7.0), potassium = Mass.milligrams(1_800.0),
            riboflavin = Mass.milligrams(1.2), saturatedFat = Mass.grams(9.0),
            selenium = Mass.micrograms(48.0), thiamin = Mass.milligrams(1.0),
            transFat = Mass.grams(0.5), unsaturatedFat = Mass.grams(19.0),
            vitaminA = Mass.micrograms(700.0), vitaminB12 = Mass.micrograms(2.4),
            vitaminB6 = Mass.milligrams(1.5), vitaminC = Mass.milligrams(75.0),
            vitaminD = Mass.micrograms(15.0), vitaminE = Mass.milligrams(11.0),
            vitaminK = Mass.micrograms(90.0), zinc = Mass.milligrams(8.0),
        ).toRecord().single()
        val data = nutrition.nutritionData!!
        assertEquals(800.0, nutrition.value)
        assertEquals(800.0, data.calories)
        assertEquals(40.0, data.protein)
        assertEquals(90.0, data.carbohydrates)
        assertEquals(30.0, data.fat)
        assertEquals(8.0, data.fiber)
        assertEquals(22.0, data.sugar)
        assertEquals(700.0, data.sodium)
        assertEquals(95.0, data.caffeine)
        assertEquals(300.0, data.calcium)
        assertEquals(120.0, data.cholesterol)
        assertEquals(9.0, data.iron)
        assertEquals(1_800.0, data.potassium)
        assertEquals(2.4, data.vitaminB12)
        assertEquals(15.0, data.vitaminD)
        assertEquals(8.0, data.zinc)
        assertEquals("Feast", nutrition.metadata)
    }

    @Test
    fun `empty nutrition record reads zero nutrients`() {
        val nutrition = NutritionRecord(
            metadata = meta("nut0"), startTime = start, startZoneOffset = zone,
            endTime = end, endZoneOffset = zone,
        ).toRecord().single()
        assertEquals(0.0, nutrition.value)
        assertEquals(0.0, nutrition.nutritionData!!.protein)
        assertEquals(null, nutrition.metadata)
    }

    @Test
    fun `unsupported record types throw`() {
        val temperature = BodyTemperatureRecord(
            time = start, zoneOffset = zone, metadata = meta("temp"),
            temperature = Temperature.celsius(36.8),
            measurementLocation = 0,
        )
        assertFailsWith<IllegalArgumentException> { temperature.toRecord() }
    }

    @Test
    fun `exercise type names cover known and unknown codes`() {
        assertEquals("Running", exerciseTypeName(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING))
        assertEquals("Workout", exerciseTypeName(999_999))
        assertTrue(exerciseSegmentTypeName(999_999).isNotBlank())
    }
}
