package com.vayunmathur.health.domain

/**
 * Typed quantities for the unit-sensitive health metrics.
 *
 * A bare [Double] carries no unit, so a centimetre value can be stored where a metre was
 * expected (and did: height needed a `/ 100` special case at the logging dialog). These
 * value classes make the unit part of the type at every logging / repository boundary:
 * callers state what they measured, and the conversion to the canonical stored unit
 * (see `StoredUnit` in `data/`) happens in exactly one place per pair.
 *
 * Pure Kotlin: no Android, no Health Connect types. The Health Connect conversions
 * (`Mass.kilograms(...)`, `Length.meters(...)`, ...) stay at the Health Connect
 * boundary in `data/HealthAPI.kt` and `sync/HealthSyncWorker.kt`, which wrap their
 * already-converted doubles in these types when building a stored record.
 */

/** Mass in kilograms: the canonical stored unit for Weight / LeanBodyMass / BoneMass / BodyWaterMass. */
@JvmInline
value class Kilograms(val value: Double)

/** Length in metres: the canonical stored unit for Height (and Elevation). */
@JvmInline
value class Meters(val value: Double) {
    /** The same length in centimetres, the unit height is entered and displayed in. */
    fun toCentimeters(): Centimeters = Centimeters(value * CENTIMETERS_PER_METER)

    /** Shared constants and helpers for Meters. */
    companion object {
        const val CENTIMETERS_PER_METER: Double = 100.0
    }
}

/** Length in centimetres: the unit height is entered and displayed in — never stored. */
@JvmInline
value class Centimeters(val value: Double) {
    /** The canonical stored form of this length. This is the one height conversion. */
    fun toMeters(): Meters = Meters(value / Meters.CENTIMETERS_PER_METER)
}

/** Blood glucose in milligrams per decilitre: the canonical stored unit for BloodGlucose. */
@JvmInline
value class MilligramsPerDeciliter(val value: Double)

/** Pressure in millimetres of mercury: systolic (stored `value`) and diastolic (`secondaryValue`). */
@JvmInline
value class MillimetersOfMercury(val value: Double)

/**
 * A percentage on the 0–100 scale Health Connect's `Percentage` uses (98.0 means 98%):
 * the canonical stored unit for OxygenSaturation and BodyFat. Not a 0.0–1.0 fraction.
 */
@JvmInline
value class Percent(val value: Double)

/** Volume in litres: the canonical stored unit for Hydration. */
@JvmInline
value class Liters(val value: Double)

/**
 * A skin-temperature *difference* in degrees Celsius, as Health Connect reports it
 * (`TemperatureDelta`): the canonical stored unit for SkinTemperature. A delta, not an
 * absolute temperature, so negative values are ordinary readings.
 */
@JvmInline
value class CelsiusDelta(val value: Double)
