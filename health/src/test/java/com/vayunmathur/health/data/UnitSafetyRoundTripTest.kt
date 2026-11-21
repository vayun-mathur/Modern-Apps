package com.vayunmathur.health.data

import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Volume
import com.vayunmathur.health.domain.CelsiusDelta
import com.vayunmathur.health.domain.Centimeters
import com.vayunmathur.health.domain.Kilograms
import com.vayunmathur.health.domain.Liters
import com.vayunmathur.health.domain.Meters
import com.vayunmathur.health.domain.MilligramsPerDeciliter
import com.vayunmathur.health.domain.MillimetersOfMercury
import com.vayunmathur.health.domain.Percent
import com.vayunmathur.health.sync.toRecord
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The checked contract for the canonical stored units ([RecordType.storedUnit]):
 * Health Connect -> Room -> Health Connect round-trips for every mirrored type,
 * the cm -> m -> cm height path, and the nutrition field pairing between the
 * HealthAPI export constructors and the HealthSyncWorker import accessors.
 */
class UnitSafetyRoundTripTest {

    private val start: Instant = Instant.parse("2026-03-04T07:00:00Z")
    private val end: Instant = Instant.parse("2026-03-04T08:00:00Z")
    private val zone: ZoneOffset = ZoneOffset.UTC
    private fun meta(id: String) = Metadata.manualEntry(id)

    // ------------------------------------------------------------------
    // Step 1: the stored-unit table
    // ------------------------------------------------------------------

    @Test
    fun `stored unit table covers every record type`() {
        // storedUnit() is an exhaustive when, so this also fails to compile if a
        // RecordType is ever added without a canonical unit.
        assertEquals(RecordType.entries.size, RecordType.entries.map { it.storedUnit() }.size)
        assertEquals(StoredUnit.Kilograms, RecordType.Weight.storedUnit().unit)
        assertEquals(StoredUnit.Percent, RecordType.BodyFat.storedUnit().unit)
        assertEquals(StoredUnit.Liters, RecordType.Hydration.storedUnit().unit)
        assertEquals(StoredUnit.MilligramsPerDeciliter, RecordType.BloodGlucose.storedUnit().unit)
        assertEquals(StoredUnit.CelsiusDelta, RecordType.SkinTemperature.storedUnit().unit)
        assertEquals(StoredUnit.Kilocalories, RecordType.Nutrition.storedUnit().unit)
    }

    @Test
    fun `height is stored in metres and entered in centimetres`() {
        val info = RecordType.Height.storedUnit()
        assertEquals(StoredUnit.Meters, info.unit)
        assertEquals("m", info.unit.ucumCode)
        assertTrue(info.note!!.contains("Stored m, entered/displayed cm"), info.note)
    }

    @Test
    fun `blood pressure secondary value is also millimetres of mercury`() {
        val info = RecordType.BloodPressure.storedUnit()
        assertEquals(StoredUnit.MillimetersOfMercury, info.unit)
        assertEquals(StoredUnit.MillimetersOfMercury, info.secondaryUnit)
        assertEquals(StoredUnit.UnitlessCode, RecordType.Exercise.storedUnit().secondaryUnit)
    }

    // ------------------------------------------------------------------
    // Step 2: typed seam conversions
    // ------------------------------------------------------------------

    @Test
    fun `centimetres convert to stored metres and back`() {
        assertEquals(Meters(1.75), Centimeters(175.0).toMeters())
        assertEquals(Centimeters(175.0), Meters(1.75).toCentimeters())
        // The factory the logging dialog reaches (via logHeight(Centimeters)) stores m…
        val stored = storedHeightRecord("h-cm", start, Centimeters(175.0))
        assertEquals(RecordType.Height, stored.type)
        assertEquals(1.75, stored.value, 1e-9)
        // …and reading it back as metres converts losslessly back to entered cm.
        assertEquals(175.0, stored.storedHeight().toCentimeters().value, 1e-9)
    }

    @Test
    fun `typed factories store the canonical doubles`() {
        assertEquals(70.0, storedWeightRecord("w", start, Kilograms(70.0)).value, 1e-9)
        assertEquals(1.8, storedHeightRecord("h", start, Meters(1.8)).value, 1e-9)
        assertEquals(20.0, storedBodyFatRecord("bf", start, Percent(20.0)).value, 1e-9)
        assertEquals(55.0, storedLeanBodyMassRecord("lbm", start, Kilograms(55.0)).value, 1e-9)
        assertEquals(3.0, storedBoneMassRecord("bm", start, Kilograms(3.0)).value, 1e-9)
        assertEquals(35.0, storedBodyWaterMassRecord("bwm", start, Kilograms(35.0)).value, 1e-9)
        assertEquals(0.5, storedHydrationRecord("hy", start, end, Liters(0.5)).value, 1e-9)
        assertEquals(
            96.0,
            storedBloodGlucoseRecord("bg", start, MilligramsPerDeciliter(96.0)).value,
            1e-9,
        )
        val bp = storedBloodPressureRecord(
            "bp", start, MillimetersOfMercury(121.0), MillimetersOfMercury(79.0)
        )
        assertEquals(121.0, bp.value, 1e-9)
        assertEquals(79.0, bp.secondaryValue, 1e-9)
        assertEquals(98.0, storedOxygenSaturationRecord("o2", start, Percent(98.0)).value, 1e-9)
        // A temperature delta may be negative; the typed factory must not clamp it.
        assertEquals(
            -0.2,
            storedSkinTemperatureRecord("st", 1, start, CelsiusDelta(-0.2)).value,
            1e-9,
        )
    }

    @Test
    fun `typed reads reject the wrong record type`() {
        val weight = storedWeightRecord("w", start, Kilograms(70.0))
        assertEquals(Kilograms(70.0), weight.storedWeight())
        assertFailsWith<IllegalArgumentException> { weight.storedHeight() }
        assertFailsWith<IllegalArgumentException> { weight.storedHydration() }
    }

    // ------------------------------------------------------------------
    // Step 3a: HC -> Room -> HC for the mirrored (writable) types
    // ------------------------------------------------------------------

    @Test
    fun `weight round-trips hc to room to hc`() {
        val room = WeightRecord(start, zone, Mass.kilograms(70.0), meta("wt")).toRecord().single()
        assertEquals(70.0, room.value, 1e-9)
        val exported = HealthAPI.weightRecord(room)
        assertEquals(70.0, exported.weight.inKilograms, 1e-9)
        assertEquals(70.0, exported.toRecord().single().value, 1e-9)
    }

    @Test
    fun `height round-trips hc to room to hc in metres`() {
        val room = HeightRecord(start, zone, Length.meters(1.8), meta("ht")).toRecord().single()
        assertEquals(1.8, room.value, 1e-9)
        val exported = HealthAPI.heightRecord(room)
        assertEquals(1.8, exported.height.inMeters, 1e-9)
        assertEquals(180.0, exported.height.inMeters.let { Meters(it).toCentimeters().value }, 1e-9)
        assertEquals(1.8, exported.toRecord().single().value, 1e-9)
    }

    @Test
    fun `body composition round-trips hc to room to hc`() {
        val fat = BodyFatRecord(start, zone, Percentage(20.0), meta("bf")).toRecord().single()
        assertEquals(20.0, HealthAPI.bodyFatRecord(fat).percentage.value, 1e-9)
        assertEquals(20.0, HealthAPI.bodyFatRecord(fat).toRecord().single().value, 1e-9)

        val lean = LeanBodyMassRecord(start, zone, Mass.kilograms(55.0), meta("lbm")).toRecord().single()
        assertEquals(55.0, HealthAPI.leanBodyMassRecord(lean).mass.inKilograms, 1e-9)
        assertEquals(55.0, HealthAPI.leanBodyMassRecord(lean).toRecord().single().value, 1e-9)

        val bone = BoneMassRecord(start, zone, Mass.kilograms(3.0), meta("bm")).toRecord().single()
        assertEquals(3.0, HealthAPI.boneMassRecord(bone).mass.inKilograms, 1e-9)
        assertEquals(3.0, HealthAPI.boneMassRecord(bone).toRecord().single().value, 1e-9)

        val water = BodyWaterMassRecord(start, zone, Mass.kilograms(35.0), meta("bwm")).toRecord().single()
        assertEquals(35.0, HealthAPI.bodyWaterMassRecord(water).mass.inKilograms, 1e-9)
        assertEquals(35.0, HealthAPI.bodyWaterMassRecord(water).toRecord().single().value, 1e-9)
    }

    @Test
    fun `hydration round-trips hc to room to hc in litres`() {
        val room = HydrationRecord(start, zone, end, zone, Volume.liters(0.5), meta("hy"))
            .toRecord().single()
        assertEquals(0.5, room.value, 1e-9)
        val exported = HealthAPI.hydrationRecord(room)
        assertEquals(0.5, exported.volume.inLiters, 1e-9)
        assertEquals(0.5, exported.toRecord().single().value, 1e-9)
    }

    // ------------------------------------------------------------------
    // Step 3b: nutrition field pairing (export constructors vs import accessors)
    // ------------------------------------------------------------------

    /** One nutrient: its stored unit plus how to read it back off each side. */
    private data class NutrientCase(
        val name: String,
        val unit: StoredUnit,
        val room: (NutritionData) -> Double,
        val hcMass: (NutritionRecord) -> Mass?,
    ) {
        /** The HC value read in this field's stored unit (kcal is energy, not a mass). */
        fun hc(rec: NutritionRecord): Double? =
            if (name == "calories") {
                rec.energy?.inKilocalories
            } else {
                when (unit) {
                    StoredUnit.Grams -> hcMass(rec)?.inGrams
                    StoredUnit.Milligrams -> hcMass(rec)?.inMilligrams
                    StoredUnit.Micrograms -> hcMass(rec)?.inMicrograms
                    else -> hcMass(rec)?.inGrams
                }
            }
    }

    private val nutrientCases: List<NutrientCase> = listOf(
        NutrientCase("protein", StoredUnit.Grams, { it.protein }, { it.protein }),
        NutrientCase("carbohydrates", StoredUnit.Grams, { it.carbohydrates }, { it.totalCarbohydrate }),
        NutrientCase("fat", StoredUnit.Grams, { it.fat }, { it.totalFat }),
        NutrientCase("fiber", StoredUnit.Grams, { it.fiber }, { it.dietaryFiber }),
        NutrientCase("sugar", StoredUnit.Grams, { it.sugar }, { it.sugar }),
        NutrientCase("sodium", StoredUnit.Milligrams, { it.sodium }, { it.sodium }),
        NutrientCase("biotin", StoredUnit.Micrograms, { it.biotin }, { it.biotin }),
        NutrientCase("caffeine", StoredUnit.Milligrams, { it.caffeine }, { it.caffeine }),
        NutrientCase("calcium", StoredUnit.Milligrams, { it.calcium }, { it.calcium }),
        NutrientCase("chloride", StoredUnit.Milligrams, { it.chloride }, { it.chloride }),
        NutrientCase("cholesterol", StoredUnit.Milligrams, { it.cholesterol }, { it.cholesterol }),
        NutrientCase("chromium", StoredUnit.Micrograms, { it.chromium }, { it.chromium }),
        NutrientCase("copper", StoredUnit.Milligrams, { it.copper }, { it.copper }),
        NutrientCase("folate", StoredUnit.Micrograms, { it.folate }, { it.folate }),
        NutrientCase("folicAcid", StoredUnit.Micrograms, { it.folicAcid }, { it.folicAcid }),
        NutrientCase("iodine", StoredUnit.Micrograms, { it.iodine }, { it.iodine }),
        NutrientCase("iron", StoredUnit.Milligrams, { it.iron }, { it.iron }),
        NutrientCase("magnesium", StoredUnit.Milligrams, { it.magnesium }, { it.magnesium }),
        NutrientCase("manganese", StoredUnit.Milligrams, { it.manganese }, { it.manganese }),
        NutrientCase("molybdenum", StoredUnit.Micrograms, { it.molybdenum }, { it.molybdenum }),
        NutrientCase("monounsaturatedFat", StoredUnit.Grams, { it.monounsaturatedFat }, { it.monounsaturatedFat }),
        NutrientCase("niacin", StoredUnit.Milligrams, { it.niacin }, { it.niacin }),
        NutrientCase("pantothenicAcid", StoredUnit.Milligrams, { it.pantothenicAcid }, { it.pantothenicAcid }),
        NutrientCase("phosphorus", StoredUnit.Milligrams, { it.phosphorus }, { it.phosphorus }),
        NutrientCase("polyunsaturatedFat", StoredUnit.Grams, { it.polyunsaturatedFat }, { it.polyunsaturatedFat }),
        NutrientCase("potassium", StoredUnit.Milligrams, { it.potassium }, { it.potassium }),
        NutrientCase("riboflavin", StoredUnit.Milligrams, { it.riboflavin }, { it.riboflavin }),
        NutrientCase("saturatedFat", StoredUnit.Grams, { it.saturatedFat }, { it.saturatedFat }),
        NutrientCase("selenium", StoredUnit.Micrograms, { it.selenium }, { it.selenium }),
        NutrientCase("thiamin", StoredUnit.Milligrams, { it.thiamin }, { it.thiamin }),
        NutrientCase("transFat", StoredUnit.Grams, { it.transFat }, { it.transFat }),
        NutrientCase("unsaturatedFat", StoredUnit.Grams, { it.unsaturatedFat }, { it.unsaturatedFat }),
        NutrientCase("vitaminA", StoredUnit.Micrograms, { it.vitaminA }, { it.vitaminA }),
        NutrientCase("vitaminB12", StoredUnit.Micrograms, { it.vitaminB12 }, { it.vitaminB12 }),
        NutrientCase("vitaminB6", StoredUnit.Milligrams, { it.vitaminB6 }, { it.vitaminB6 }),
        NutrientCase("vitaminC", StoredUnit.Milligrams, { it.vitaminC }, { it.vitaminC }),
        NutrientCase("vitaminD", StoredUnit.Micrograms, { it.vitaminD }, { it.vitaminD }),
        NutrientCase("vitaminE", StoredUnit.Milligrams, { it.vitaminE }, { it.vitaminE }),
        NutrientCase("vitaminK", StoredUnit.Micrograms, { it.vitaminK }, { it.vitaminK }),
        NutrientCase("zinc", StoredUnit.Milligrams, { it.zinc }, { it.zinc }),
        // Energy is not a Mass: hcMass is unused for calories (see NutrientCase.hc).
        NutrientCase("calories", StoredUnit.Kilocalories, { it.calories }, { null }),
    )

    /** Distinct sentinel per field (index + 1) so a swapped pairing cannot pass. */
    private fun sentinel(index: Int): Double = (index + 1).toDouble()

    private fun hcNutritionWithSentinels(): NutritionRecord {
        fun g(i: Int) = Mass.grams(sentinel(i))
        fun mg(i: Int) = Mass.milligrams(sentinel(i))
        fun ug(i: Int) = Mass.micrograms(sentinel(i))
        // Argument order follows nutrientCases; values are (case index + 1).
        return NutritionRecord(
            metadata = meta("nut-pair"), startTime = start, startZoneOffset = zone,
            endTime = end, endZoneOffset = zone, name = "Pairing Meal",
            protein = g(0), totalCarbohydrate = g(1), totalFat = g(2), dietaryFiber = g(3),
            sugar = g(4), sodium = mg(5), biotin = ug(6), caffeine = mg(7), calcium = mg(8),
            chloride = mg(9), cholesterol = mg(10), chromium = ug(11), copper = mg(12),
            folate = ug(13), folicAcid = ug(14), iodine = ug(15), iron = mg(16),
            magnesium = mg(17), manganese = mg(18), molybdenum = ug(19),
            monounsaturatedFat = g(20), niacin = mg(21), pantothenicAcid = mg(22),
            phosphorus = mg(23), polyunsaturatedFat = g(24), potassium = mg(25),
            riboflavin = mg(26), saturatedFat = g(27), selenium = ug(28), thiamin = mg(29),
            transFat = g(30), unsaturatedFat = g(31), vitaminA = ug(32), vitaminB12 = ug(33),
            vitaminB6 = mg(34), vitaminC = mg(35), vitaminD = ug(36), vitaminE = mg(37),
            vitaminK = ug(38), zinc = mg(39), energy = Energy.kilocalories(sentinel(40)),
        )
    }

    private fun roomNutritionWithSentinels(): NutritionData = NutritionData(
        protein = sentinel(0), carbohydrates = sentinel(1), fat = sentinel(2),
        fiber = sentinel(3), sugar = sentinel(4), sodium = sentinel(5), biotin = sentinel(6),
        caffeine = sentinel(7), calcium = sentinel(8), chloride = sentinel(9),
        cholesterol = sentinel(10), chromium = sentinel(11), copper = sentinel(12),
        folate = sentinel(13), folicAcid = sentinel(14), iodine = sentinel(15),
        iron = sentinel(16), magnesium = sentinel(17), manganese = sentinel(18),
        molybdenum = sentinel(19), monounsaturatedFat = sentinel(20), niacin = sentinel(21),
        pantothenicAcid = sentinel(22), phosphorus = sentinel(23),
        polyunsaturatedFat = sentinel(24), potassium = sentinel(25), riboflavin = sentinel(26),
        saturatedFat = sentinel(27), selenium = sentinel(28), thiamin = sentinel(29),
        transFat = sentinel(30), unsaturatedFat = sentinel(31), vitaminA = sentinel(32),
        vitaminB12 = sentinel(33), vitaminB6 = sentinel(34), vitaminC = sentinel(35),
        vitaminD = sentinel(36), vitaminE = sentinel(37), vitaminK = sentinel(38),
        zinc = sentinel(39), calories = sentinel(40),
    )

    @Test
    fun `nutrition field pairing table matches the stored unit table`() {
        assertEquals(nutrientCases.map { it.name }.toSet(), NutritionStoredUnits.byField.keys)
        nutrientCases.forEach { case ->
            assertEquals(case.unit, NutritionStoredUnits.byField[case.name], case.name)
        }
    }

    @Test
    fun `nutrition import pairs every hc field with the same room field`() {
        val imported = hcNutritionWithSentinels().toRecord().single()
        assertEquals(sentinel(40), imported.value, 1e-9, "energy -> Record.value (kcal)")
        val data = assertNotNull(imported.nutritionData)
        nutrientCases.forEachIndexed { index, case ->
            assertEquals(sentinel(index), case.room(data), 1e-9, "import field ${case.name}")
        }
        // …and the full HC -> Room -> HC trip returns every field to its HC accessor.
        val exported = assertNotNull(HealthAPI.nutritionRecord(imported))
        nutrientCases.forEachIndexed { index, case ->
            assertEquals(sentinel(index), case.hc(exported) ?: Double.NaN, 1e-9, "round-trip ${case.name}")
        }
    }

    @Test
    fun `nutrition export pairs every room field with the same hc field`() {
        val data = roomNutritionWithSentinels()
        val record = Record(
            id = "nut-export", index = 0, type = RecordType.Nutrition,
            startTime = start, endTime = end, value = data.calories,
            nutritionData = data, metadata = "Pairing Meal",
        )
        val exported = assertNotNull(HealthAPI.nutritionRecord(record))
        nutrientCases.forEachIndexed { index, case ->
            assertEquals(sentinel(index), case.hc(exported) ?: Double.NaN, 1e-9, "export field ${case.name}")
        }
        // …and importing that export reproduces the room fields (Room -> HC -> Room).
        val reimported = assertNotNull(exported.toRecord().single().nutritionData)
        nutrientCases.forEachIndexed { index, case ->
            assertEquals(sentinel(index), case.room(reimported), 1e-9, "reimport field ${case.name}")
        }
    }
}
