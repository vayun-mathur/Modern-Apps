package com.vayunmathur.health.util

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.Volume
import com.vayunmathur.health.data.RecordType
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HealthRecordConversionTest {

    private val start: Instant = Instant.parse("2026-03-04T07:00:00Z")
    private val end: Instant = Instant.parse("2026-03-04T08:00:00Z")
    private val zone: ZoneOffset = ZoneOffset.UTC
    private fun meta(id: String) = Metadata.manualEntry(id)

    @Test
    fun `activity records convert`() {
        assertEquals(5000.0, StepsRecord(start, zone, end, zone, 5000, meta("s1")).toRecord().single().value)
        assertEquals(RecordType.Steps, StepsRecord(start, zone, end, zone, 5000, meta("s1")).toRecord().single().type)
        assertEquals(20.0, WheelchairPushesRecord(start, zone, end, zone, 20, meta("w1")).toRecord().single().value)
        assertEquals(2.5, DistanceRecord(start, zone, end, zone, Length.kilometers(2.5), meta("d1")).toRecord().single().value)
        assertEquals(300.0, TotalCaloriesBurnedRecord(start, zone, end, zone, Energy.kilocalories(300.0), meta("t1")).toRecord().single().value)
        assertEquals(200.0, ActiveCaloriesBurnedRecord(start, zone, end, zone, Energy.kilocalories(200.0), meta("a1")).toRecord().single().value)
        assertEquals(12.0, FloorsClimbedRecord(start, zone, end, zone, 12.0, meta("f1")).toRecord().single().value)
        assertEquals(50.0, ElevationGainedRecord(start, zone, end, zone, Length.meters(50.0), meta("e1")).toRecord().single().value)
    }

    @Test
    fun `vitals records convert`() {
        val hr = HeartRateRecord(start, zone, end, zone, listOf(HeartRateRecord.Sample(start, 60), HeartRateRecord.Sample(end, 90)), meta("hr1")).toRecord()
        assertEquals(2, hr.size)
        assertEquals(60.0, hr[0].value)
        assertEquals(90.0, hr[1].value)
        assertEquals(1, hr[1].index)
        assertEquals(58.0, RestingHeartRateRecord(start, zone, 58, meta("rhr")).toRecord().single().value)
        assertEquals(42.0, HeartRateVariabilityRmssdRecord(start, zone, 42.0, meta("hrv")).toRecord().single().value)
        assertEquals(14.0, RespiratoryRateRecord(start, zone, 14.0, meta("rr")).toRecord().single().value)
        assertEquals(98.0, OxygenSaturationRecord(start, zone, Percentage(98.0), meta("o2")).toRecord().single().value)
    }

    @Test
    fun `body records convert`() {
        assertEquals(70.0, WeightRecord(start, zone, Mass.kilograms(70.0), meta("wt")).toRecord().single().value)
        assertEquals(1.8, HeightRecord(start, zone, Length.meters(1.8), meta("ht")).toRecord().single().value)
        assertEquals(20.0, BodyFatRecord(start, zone, Percentage(20.0), meta("bf")).toRecord().single().value)
        assertEquals(55.0, LeanBodyMassRecord(start, zone, Mass.kilograms(55.0), meta("lbm")).toRecord().single().value)
        assertEquals(3.0, BoneMassRecord(start, zone, Mass.kilograms(3.0), meta("bm")).toRecord().single().value)
        assertEquals(35.0, BodyWaterMassRecord(start, zone, Mass.kilograms(35.0), meta("bwm")).toRecord().single().value)
    }

    @Test
    fun `sleep mindfulness exercise hydration nutrition convert`() {
        val sleep = SleepSessionRecord(metadata = meta("sl"), startTime = start, startZoneOffset = zone, endTime = end, endZoneOffset = zone, stages = listOf(SleepSessionRecord.Stage(startTime = start, endTime = end, stage = SleepSessionRecord.STAGE_TYPE_DEEP)), title = "Sleep title", notes = "notes").toRecord().single()
        assertEquals(RecordType.Sleep, sleep.type)
        assertEquals(1.0, sleep.value)
        assertNotNull(sleep.sleepData)
        assertEquals(3_600_000L, sleep.sleepData!!.deepDurationMillis)

        val mind = MindfulnessSessionRecord(metadata = meta("mf"), startTime = start, startZoneOffset = zone, endTime = end, endZoneOffset = zone, mindfulnessSessionType = MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MEDITATION, title = "t", notes = "n").toRecord().single()
        assertEquals(60.0, mind.value)

        val exercise = ExerciseSessionRecord(metadata = meta("ex"), startTime = start, startZoneOffset = zone, endTime = end, endZoneOffset = zone, exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, title = "Run", notes = "notes", segments = listOf(androidx.health.connect.client.records.ExerciseSegment(startTime = start, endTime = end, segmentType = androidx.health.connect.client.records.ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING, repetitions = 5)), laps = listOf(androidx.health.connect.client.records.ExerciseLap(startTime = start, endTime = end, length = Length.meters(1000.0)))).toRecord().single()
        assertEquals(60.0, exercise.value)
        assertEquals("Run", exercise.exerciseData!!.title)
        assertTrue(exercise.exerciseData!!.segmentsJson!!.contains("segmentType"))

        assertEquals(0.5, HydrationRecord(start, zone, end, zone, Volume.liters(0.5), meta("hy")).toRecord().single().value)

        val nutrition = NutritionRecord(metadata = meta("nu"), startTime = start, startZoneOffset = zone, endTime = end, endZoneOffset = zone, name = "Meal", energy = Energy.kilocalories(400.0), protein = Mass.grams(20.0), totalCarbohydrate = Mass.grams(50.0), totalFat = Mass.grams(10.0)).toRecord().single()
        assertEquals(400.0, nutrition.value)
        assertEquals(20.0, nutrition.nutritionData!!.protein)
        assertEquals("Meal", nutrition.metadata)
    }
}
