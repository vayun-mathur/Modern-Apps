package com.vayunmathur.health.data

/**
 * A physical unit a stored [Record.value] (or [Record.secondaryValue]) is expressed in.
 *
 * [ucumCode] is the UCUM code — the same code FHIR `valueQuantity` carries
 * (`domain/FhirObservations.kt`, system `http://unitsofmeasure.org`) — and
 * [displaySymbol] is the symbol the UI shows for it (see `HealthMetricConfig.unit`).
 * Count-like metrics use [Count] (UCUM `1`, a dimensionless count of things).
 */
enum class StoredUnit(val ucumCode: String, val displaySymbol: String) {
    Count("1", "count"),
    Kilometers("km", "km"),
    Kilocalories("kcal", "cal"),
    KilocaloriesPerDay("kcal/d", "cal/day"),
    Meters("m", "m"),
    BeatsPerMinute("/min", "bpm"),
    Milliseconds("ms", "ms"),
    BreathsPerMinute("/min", "brpm"),
    Percent("%", "%"),
    MillimetersOfMercury("mm[Hg]", "mmHg"),
    MilligramsPerDeciliter("mg/dL", "mg/dL"),
    MillilitersPerMinuteKilogram("mL/(kg.min)", "ml/kg/min"),
    CelsiusDelta("Cel", "°C"),
    Kilograms("kg", "kg"),
    Grams("g", "g"),
    Milligrams("mg", "mg"),
    Micrograms("ug", "µg"),
    Hours("h", "hr"),
    Minutes("min", "min"),
    Liters("L", "L"),

    /** No physical unit: the number is a code (Exercise `secondaryValue` is an exercise-type id). */
    UnitlessCode("1", ""),
}

/**
 * The canonical stored unit of one [RecordType]: what [Record.value] means, what
 * [Record.secondaryValue] means when it is used at all, and any entry/display unit
 * that differs from the stored one.
 */
data class StoredUnitInfo(
    val unit: StoredUnit,
    val secondaryUnit: StoredUnit? = null,
    val note: String? = null,
)

/**
 * THE canonical-unit table: the stored unit of every [RecordType], enforced at the
 * repository boundary by the typed factories in `TypedRecords.kt`.
 *
 * This is a documented invariant, not a schema change: [Record] keeps its plain
 * `value`/`secondaryValue` doubles and there are deliberately no value+unit columns.
 * The invariant is already guaranteed from outside — Health Connect hands us typed
 * units (`Mass.inKilograms`, `Length.inMeters`, `Pressure.inMillimetersOfMercury`, …)
 * and FHIR quantities carry UCUM codes — so every producer converts to exactly the
 * unit below at its conversion line, and this table is what those lines agree on.
 * The round-trip tests (`UnitSafetyRoundTripTest`) are the checked contract for it.
 *
 * Height is the one metric whose stored unit differs from its entered/displayed
 * unit: stored metres, entered and displayed in centimetres (`HealthMetricConfig.HEIGHT`
 * shows "cm", `HealthChartData.scaleForDisplay` multiplies stored metres by 100).
 * That conversion lives only in `Centimeters.toMeters()` / `Meters.toCentimeters()`.
 */
fun RecordType.storedUnit(): StoredUnitInfo = when (this) {
    RecordType.Steps -> StoredUnitInfo(StoredUnit.Count, note = "Step count.")
    RecordType.Wheelchair -> StoredUnitInfo(StoredUnit.Count, note = "Wheelchair push count.")
    RecordType.Distance -> StoredUnitInfo(StoredUnit.Kilometers)
    RecordType.CaloriesTotal -> StoredUnitInfo(StoredUnit.Kilocalories)
    RecordType.CaloriesActive -> StoredUnitInfo(StoredUnit.Kilocalories)
    RecordType.CaloriesBasal -> StoredUnitInfo(StoredUnit.KilocaloriesPerDay)
    RecordType.Floors -> StoredUnitInfo(StoredUnit.Count, note = "Floors climbed.")
    RecordType.Elevation -> StoredUnitInfo(StoredUnit.Meters)
    RecordType.HeartRate -> StoredUnitInfo(StoredUnit.BeatsPerMinute)
    RecordType.RestingHeartRate -> StoredUnitInfo(StoredUnit.BeatsPerMinute)
    RecordType.HeartRateVariabilityRmssd -> StoredUnitInfo(StoredUnit.Milliseconds)
    RecordType.RespiratoryRate -> StoredUnitInfo(StoredUnit.BreathsPerMinute)
    RecordType.OxygenSaturation -> StoredUnitInfo(
        StoredUnit.Percent, note = "0–100 scale, not a fraction."
    )
    RecordType.BloodPressure -> StoredUnitInfo(
        StoredUnit.MillimetersOfMercury,
        secondaryUnit = StoredUnit.MillimetersOfMercury,
        note = "value = systolic, secondaryValue = diastolic, both mmHg.",
    )
    RecordType.BloodGlucose -> StoredUnitInfo(StoredUnit.MilligramsPerDeciliter)
    RecordType.Vo2Max -> StoredUnitInfo(StoredUnit.MillilitersPerMinuteKilogram)
    RecordType.SkinTemperature -> StoredUnitInfo(
        StoredUnit.CelsiusDelta, note = "Temperature delta vs baseline, not an absolute temperature."
    )
    RecordType.Weight -> StoredUnitInfo(StoredUnit.Kilograms)
    RecordType.Height -> StoredUnitInfo(
        StoredUnit.Meters,
        note = "Stored m, entered/displayed cm: entry converts via Centimeters.toMeters().",
    )
    RecordType.BodyFat -> StoredUnitInfo(StoredUnit.Percent, note = "0–100 scale, not a fraction.")
    RecordType.LeanBodyMass -> StoredUnitInfo(StoredUnit.Kilograms)
    RecordType.BoneMass -> StoredUnitInfo(StoredUnit.Kilograms)
    RecordType.BodyWaterMass -> StoredUnitInfo(StoredUnit.Kilograms)
    RecordType.Sleep -> StoredUnitInfo(
        StoredUnit.Hours, note = "Session duration; stage durations are SleepData millis."
    )
    RecordType.Mindfulness -> StoredUnitInfo(StoredUnit.Minutes, note = "Session duration.")
    RecordType.Hydration -> StoredUnitInfo(StoredUnit.Liters)
    RecordType.Nutrition -> StoredUnitInfo(
        StoredUnit.Kilocalories,
        note = "value = energy; NutritionData fields use NutritionStoredUnits (g / mg / µg / kcal).",
    )
    RecordType.Exercise -> StoredUnitInfo(
        StoredUnit.Minutes,
        secondaryUnit = StoredUnit.UnitlessCode,
        note = "value = duration minutes; secondaryValue = Health Connect exercise-type code.",
    )
}

/**
 * The stored unit of every `NutritionData` field, keyed by field name.
 *
 * Health Connect splits nutrients across three mass units, so the field — not the
 * record — carries the unit: macros in grams, most micronutrients in milligrams,
 * trace elements and several vitamins in micrograms, energy in kilocalories. The
 * Health Connect import (`HealthSyncWorker.toNutritionRecord`) reads each field
 * with the matching accessor (`inGrams` / `inMilligrams` / `inMicrograms`) and the
 * export (`HealthAPI` nutrition builder) writes it with the matching constructor;
 * `UnitSafetyRoundTripTest` pairs those two sides field by field.
 */
object NutritionStoredUnits {
    val byField: Map<String, StoredUnit> = mapOf(
        "protein" to StoredUnit.Grams,
        "carbohydrates" to StoredUnit.Grams,
        "fat" to StoredUnit.Grams,
        "fiber" to StoredUnit.Grams,
        "sugar" to StoredUnit.Grams,
        "sodium" to StoredUnit.Milligrams,
        "biotin" to StoredUnit.Micrograms,
        "caffeine" to StoredUnit.Milligrams,
        "calcium" to StoredUnit.Milligrams,
        "chloride" to StoredUnit.Milligrams,
        "cholesterol" to StoredUnit.Milligrams,
        "chromium" to StoredUnit.Micrograms,
        "copper" to StoredUnit.Milligrams,
        "folate" to StoredUnit.Micrograms,
        "folicAcid" to StoredUnit.Micrograms,
        "iodine" to StoredUnit.Micrograms,
        "iron" to StoredUnit.Milligrams,
        "magnesium" to StoredUnit.Milligrams,
        "manganese" to StoredUnit.Milligrams,
        "molybdenum" to StoredUnit.Micrograms,
        "monounsaturatedFat" to StoredUnit.Grams,
        "niacin" to StoredUnit.Milligrams,
        "pantothenicAcid" to StoredUnit.Milligrams,
        "phosphorus" to StoredUnit.Milligrams,
        "polyunsaturatedFat" to StoredUnit.Grams,
        "potassium" to StoredUnit.Milligrams,
        "riboflavin" to StoredUnit.Milligrams,
        "saturatedFat" to StoredUnit.Grams,
        "selenium" to StoredUnit.Micrograms,
        "thiamin" to StoredUnit.Milligrams,
        "transFat" to StoredUnit.Grams,
        "unsaturatedFat" to StoredUnit.Grams,
        "vitaminA" to StoredUnit.Micrograms,
        "vitaminB12" to StoredUnit.Micrograms,
        "vitaminB6" to StoredUnit.Milligrams,
        "vitaminC" to StoredUnit.Milligrams,
        "vitaminD" to StoredUnit.Micrograms,
        "vitaminE" to StoredUnit.Milligrams,
        "vitaminK" to StoredUnit.Micrograms,
        "zinc" to StoredUnit.Milligrams,
        "calories" to StoredUnit.Kilocalories,
    )
}
