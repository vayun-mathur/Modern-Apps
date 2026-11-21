package com.vayunmathur.health.data

import com.vayunmathur.health.domain.CelsiusDelta
import com.vayunmathur.health.domain.Centimeters
import com.vayunmathur.health.domain.Kilograms
import com.vayunmathur.health.domain.Liters
import com.vayunmathur.health.domain.Meters
import com.vayunmathur.health.domain.MilligramsPerDeciliter
import com.vayunmathur.health.domain.MillimetersOfMercury
import com.vayunmathur.health.domain.Percent
import java.time.Instant

/**
 * The typed write seam for unit-sensitive records — the one place a typed quantity
 * becomes a stored [Record] (and, via the `stored…()` accessors below, the one place
 * a stored [Record] is read back as a typed quantity).
 *
 * Why this exists: [Record.value] is a bare [Double], so before this seam a caller
 * could store centimetres where metres were expected and nothing anywhere would
 * notice — the logging dialog carried a hand-written `/ 100` for height to prevent
 * exactly that. Now the unit-sensitive producers (Health Connect import in
 * `sync/HealthSyncWorker.kt`, manual logging in `platform/HealthViewModel.kt`, and
 * the Health Connect export in `HealthAPI`) build and read records only through
 * these functions, each of which pins the canonical stored unit from
 * [RecordType.storedUnit]. The conversions themselves do not change: producers wrap
 * the double they already computed (`weight.inKilograms`, `height.inMeters`, …)
 * in the matching value class at the same line.
 *
 * Direct `Record(...)` construction remains available for the types that are not
 * unit-sensitive (counts, durations, codes, the Nutrition/Sleep/Exercise payloads —
 * Nutrition's per-field units live in [NutritionStoredUnits]) and for test fixtures.
 * New writes of a unit-sensitive type must go through here instead: constructing
 * one directly re-opens the unit hole this seam closes.
 */

// ---------------------------------------------------------------------------
// Body composition (stored kg / m / %, see StoredUnit table)
// ---------------------------------------------------------------------------

/** A Weight record: [weight] stored as kilograms. */
fun storedWeightRecord(id: String, time: Instant, weight: Kilograms, metadata: String? = "Weight"): Record =
    Record(id, 0, RecordType.Weight, time, time, weight.value, metadata = metadata)

/** A Height record: [height] stored as metres. */
fun storedHeightRecord(id: String, time: Instant, height: Meters, metadata: String? = "Height"): Record =
    Record(id, 0, RecordType.Height, time, time, height.value, metadata = metadata)

/** A Height record from the entered/displayed unit: centimetres in, stored metres out. */
fun storedHeightRecord(id: String, time: Instant, height: Centimeters, metadata: String? = "Height"): Record =
    storedHeightRecord(id, time, height.toMeters(), metadata)

/** A BodyFat record: [bodyFat] stored as a 0–100 percent. */
fun storedBodyFatRecord(id: String, time: Instant, bodyFat: Percent, metadata: String? = "Body Fat"): Record =
    Record(id, 0, RecordType.BodyFat, time, time, bodyFat.value, metadata = metadata)

/** A LeanBodyMass record: [mass] stored as kilograms. */
fun storedLeanBodyMassRecord(id: String, time: Instant, mass: Kilograms, metadata: String? = "Lean Body Mass"): Record =
    Record(id, 0, RecordType.LeanBodyMass, time, time, mass.value, metadata = metadata)

/** A BoneMass record: [mass] stored as kilograms. */
fun storedBoneMassRecord(id: String, time: Instant, mass: Kilograms, metadata: String? = "Bone Mass"): Record =
    Record(id, 0, RecordType.BoneMass, time, time, mass.value, metadata = metadata)

/** A BodyWaterMass record: [mass] stored as kilograms. */
fun storedBodyWaterMassRecord(id: String, time: Instant, mass: Kilograms, metadata: String? = "Body Water Mass"): Record =
    Record(id, 0, RecordType.BodyWaterMass, time, time, mass.value, metadata = metadata)

// ---------------------------------------------------------------------------
// Vitals (stored mg/dL / mmHg / % / °C-delta)
// ---------------------------------------------------------------------------

/** A BloodGlucose record: [level] stored as milligrams per decilitre. */
fun storedBloodGlucoseRecord(
    id: String,
    time: Instant,
    level: MilligramsPerDeciliter,
    metadata: String? = "Blood Glucose",
): Record = Record(id, 0, RecordType.BloodGlucose, time, time, level.value, metadata = metadata)

/** A BloodPressure record: systolic in `value`, diastolic in `secondaryValue`, both mmHg. */
fun storedBloodPressureRecord(
    id: String,
    time: Instant,
    systolic: MillimetersOfMercury,
    diastolic: MillimetersOfMercury,
    metadata: String? = "Blood Pressure",
): Record = Record(
    id, 0, RecordType.BloodPressure, time, time,
    systolic.value, diastolic.value, metadata = metadata,
)

/** An OxygenSaturation record: [saturation] stored as a 0–100 percent. */
fun storedOxygenSaturationRecord(
    id: String,
    time: Instant,
    saturation: Percent,
    metadata: String? = "Oxygen Saturation",
): Record = Record(id, 0, RecordType.OxygenSaturation, time, time, saturation.value, metadata = metadata)

/** One SkinTemperature sample ([index] within its source record): a Celsius delta. */
fun storedSkinTemperatureRecord(
    id: String,
    index: Int,
    time: Instant,
    delta: CelsiusDelta,
    metadata: String? = "Skin Temperature",
): Record = Record(id, index, RecordType.SkinTemperature, time, time, delta.value, metadata = metadata)

// ---------------------------------------------------------------------------
// Hydration (stored L)
// ---------------------------------------------------------------------------

/** A Hydration record over [start]–[end]: [volume] stored as litres. */
fun storedHydrationRecord(
    id: String,
    start: Instant,
    end: Instant,
    volume: Liters,
    metadata: String? = "Hydration",
): Record = Record(id, 0, RecordType.Hydration, start, end, volume.value, metadata = metadata)

// ---------------------------------------------------------------------------
// Typed reads back out of a stored record (the Health Connect export side)
// ---------------------------------------------------------------------------

private fun Record.requireType(expected: RecordType) {
    require(type == expected) { "Expected a $expected record, was $type" }
}

/** This Weight record's stored kilograms. */
fun Record.storedWeight(): Kilograms {
    requireType(RecordType.Weight)
    return Kilograms(value)
}

/** This Height record's stored metres. */
fun Record.storedHeight(): Meters {
    requireType(RecordType.Height)
    return Meters(value)
}

/** This BodyFat record's stored 0–100 percent. */
fun Record.storedBodyFat(): Percent {
    requireType(RecordType.BodyFat)
    return Percent(value)
}

/** This LeanBodyMass record's stored kilograms. */
fun Record.storedLeanBodyMass(): Kilograms {
    requireType(RecordType.LeanBodyMass)
    return Kilograms(value)
}

/** This BoneMass record's stored kilograms. */
fun Record.storedBoneMass(): Kilograms {
    requireType(RecordType.BoneMass)
    return Kilograms(value)
}

/** This BodyWaterMass record's stored kilograms. */
fun Record.storedBodyWaterMass(): Kilograms {
    requireType(RecordType.BodyWaterMass)
    return Kilograms(value)
}

/** This Hydration record's stored litres. */
fun Record.storedHydration(): Liters {
    requireType(RecordType.Hydration)
    return Liters(value)
}
