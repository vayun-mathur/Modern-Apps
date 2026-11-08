package com.vayunmathur.health.util

import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose as BloodGlucoseUnit
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Pressure
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import com.vayunmathur.health.data.RecordType
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversionRound2Test {

    private val start: Instant = Instant.parse("2026-03-04T07:00:00Z")
    private val end: Instant = Instant.parse("2026-03-04T08:00:00Z")
    private val zone: ZoneOffset = ZoneOffset.UTC
    private fun meta(id: String) = Metadata.manualEntry(id)

    @Test
    fun `blood pressure converts systolic over diastolic`() {
        val record = BloodPressureRecord(
            metadata = meta("bp2"), time = start, zoneOffset = zone,
            systolic = Pressure.millimetersOfMercury(121.0),
            diastolic = Pressure.millimetersOfMercury(79.0),
            bodyPosition = BloodPressureRecord.BODY_POSITION_STANDING_UP,
            measurementLocation = BloodPressureRecord.MEASUREMENT_LOCATION_RIGHT_WRIST,
        ).toRecord().single()
        assertEquals(RecordType.BloodPressure, record.type)
        assertEquals(121.0, record.value)
        assertEquals(79.0, record.secondaryValue)
    }

    @Test
    fun `blood glucose and vo2 convert`() {
        val glucose = BloodGlucoseRecord(
            metadata = meta("bg2"), time = start, zoneOffset = zone,
            level = BloodGlucoseUnit.milligramsPerDeciliter(96.0),
            specimenSource = BloodGlucoseRecord.SPECIMEN_SOURCE_SERUM,
        ).toRecord().single()
        assertEquals(96.0, glucose.value)
        val vo2 = Vo2MaxRecord(
            metadata = meta("vo2-2"), time = start, zoneOffset = zone,
            vo2MillilitersPerMinuteKilogram = 44.0,
            measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_METABOLIC_CART,
        ).toRecord().single()
        assertEquals(44.0, vo2.value)
    }

    @Test
    fun `skin temperature deltas convert per sample`() {
        val record = SkinTemperatureRecord(
            metadata = meta("skin2"), startTime = start, startZoneOffset = zone,
            endTime = end, endZoneOffset = zone,
            baseline = Temperature.celsius(36.4),
            deltas = listOf(
                SkinTemperatureRecord.Delta(time = start, delta = TemperatureDelta.celsius(0.3)),
                SkinTemperatureRecord.Delta(time = start.plusSeconds(1800), delta = TemperatureDelta.celsius(-0.2)),
            ),
            measurementLocation = SkinTemperatureRecord.MEASUREMENT_LOCATION_FINGER,
        ).toRecord()
        assertEquals(2, record.size)
        assertEquals(0.3, record[0].value)
        assertEquals(-0.2, record[1].value)
    }

    @Test
    fun `basal metabolic rate converts from power`() {
        val watts = 80.0
        val record = BasalMetabolicRateRecord(
            metadata = meta("bmr2"), time = start, zoneOffset = zone,
            basalMetabolicRate = Power.watts(watts),
        ).toRecord().single()
        assertEquals(RecordType.CaloriesBasal, record.type)
        // 80 W over a day is about 1653 kcal/day; the converter must produce it.
        assertTrue(record.value > 1600.0 && record.value < 1700.0, "bmr was ${record.value}")
    }

    @Test
    fun `nutrition with no nutrients reads zeros and with full nutrients maps them`() {
        val empty = NutritionRecord(
            metadata = meta("nut-empty"), startTime = start, startZoneOffset = zone,
            endTime = end, endZoneOffset = zone, name = "Empty Meal",
        ).toRecord().single()
        assertEquals(0.0, empty.value)
        assertEquals(0.0, empty.nutritionData!!.protein)

        val full = NutritionRecord(
            metadata = meta("nut-full"), startTime = start, startZoneOffset = zone,
            endTime = end, endZoneOffset = zone, name = "Full Meal",
            energy = androidx.health.connect.client.units.Energy.kilocalories(500.0),
            protein = androidx.health.connect.client.units.Mass.grams(30.0),
            totalCarbohydrate = androidx.health.connect.client.units.Mass.grams(60.0),
            totalFat = androidx.health.connect.client.units.Mass.grams(15.0),
            dietaryFiber = androidx.health.connect.client.units.Mass.grams(8.0),
            sugar = androidx.health.connect.client.units.Mass.grams(12.0),
            sodium = androidx.health.connect.client.units.Mass.milligrams(400.0),
            caffeine = androidx.health.connect.client.units.Mass.milligrams(90.0),
            calcium = androidx.health.connect.client.units.Mass.milligrams(200.0),
            cholesterol = androidx.health.connect.client.units.Mass.milligrams(50.0),
            iron = androidx.health.connect.client.units.Mass.milligrams(6.0),
            potassium = androidx.health.connect.client.units.Mass.milligrams(700.0),
            vitaminC = androidx.health.connect.client.units.Mass.milligrams(60.0),
            vitaminD = androidx.health.connect.client.units.Mass.micrograms(10.0),
            zinc = androidx.health.connect.client.units.Mass.milligrams(5.0),
        ).toRecord().single()
        assertEquals(500.0, full.value)
        assertEquals(30.0, full.nutritionData!!.protein)
        assertEquals(90.0, full.nutritionData!!.caffeine)
        assertEquals(10.0, full.nutritionData!!.vitaminD)
    }
}
