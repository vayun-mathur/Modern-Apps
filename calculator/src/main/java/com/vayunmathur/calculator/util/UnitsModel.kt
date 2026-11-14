package com.vayunmathur.calculator.util

import kotlin.math.floor
import kotlin.math.pow

/** The seven SI base dimensions, plus INFORMATION so bytes/bits are first-class, and
 * CURRENCY for the live money converter (converter-only; never used inside equations). */
enum class BaseDim { LENGTH, MASS, TIME, CURRENT, TEMPERATURE, AMOUNT, LUMINOUS, INFORMATION, CURRENCY }

/**
 * A product of base dimensions with integer exponents (so speed is `LENGTH·TIME⁻¹`). Zero
 * exponents are dropped, so structural [equals] doubles as dimensional compatibility.
 */
class Dimension private constructor(val exponents: Map<BaseDim, Int>) {

    val isDimensionless: Boolean get() = exponents.isEmpty()

    operator fun times(other: Dimension) = of(merge(exponents, other.exponents, 1))
    operator fun div(other: Dimension) = of(merge(exponents, other.exponents, -1))
    fun pow(n: Int) = of(exponents.mapValues { it.value * n })

    override fun equals(other: Any?) = other is Dimension && other.exponents == exponents
    override fun hashCode() = exponents.hashCode()
    override fun toString() =
        if (isDimensionless) "1" else exponents.entries.joinToString("·") { "${it.key}^${it.value}" }

    /** Shared dimensionless value and factories for [Dimension]. */
    companion object {
        val NONE = Dimension(emptyMap())

        fun of(map: Map<BaseDim, Int>) = Dimension(map.filterValues { it != 0 })
        private fun base(d: BaseDim) = of(mapOf(d to 1))

        private fun merge(a: Map<BaseDim, Int>, b: Map<BaseDim, Int>, sign: Int): Map<BaseDim, Int> {
            val out = a.toMutableMap()
            for ((k, v) in b) out[k] = (out[k] ?: 0) + sign * v
            return out
        }

        // Base dimensions.
        val LENGTH = base(BaseDim.LENGTH)
        val MASS = base(BaseDim.MASS)
        val TIME = base(BaseDim.TIME)
        val CURRENT = base(BaseDim.CURRENT)
        val TEMPERATURE = base(BaseDim.TEMPERATURE)
        val AMOUNT = base(BaseDim.AMOUNT)
        val INFORMATION = base(BaseDim.INFORMATION)
        val CURRENCY = base(BaseDim.CURRENCY)

        // Common derived dimensions.
        val AREA = LENGTH.pow(2)
        val VOLUME = LENGTH.pow(3)
        val SPEED = LENGTH / TIME
        val FREQUENCY = TIME.pow(-1)
        val FORCE = MASS * LENGTH / TIME.pow(2)
        val ENERGY = FORCE * LENGTH
        val POWER = ENERGY / TIME
        val PRESSURE = FORCE / AREA
        val CHARGE = CURRENT * TIME
        val VOLTAGE = POWER / CURRENT
        val RESISTANCE = VOLTAGE / CURRENT
        val CAPACITANCE = CHARGE / VOLTAGE
    }
}

/**
 * A magnitude with a [dimension], as produced by evaluating an expression node.
 *
 * [value] is the linear magnitude in coherent base units (m, kg, s, …). Temperature is affine
 * and gets special treatment: [value] is a **kelvin-sized delta** and [tempOffsetK] distinguishes
 * an *absolute* temperature (`tempOffsetK != null`, absolute kelvin = `value + tempOffsetK`) from
 * a *delta* (`tempOffsetK == null`).
 *
 * The user's rule for `+`/`-` chains of temperatures (example `200k - 5c → 195K`): the first
 * temperature is absolute; each one added/subtracted onto it contributes only its degree-size
 * (its [value]), so `5c` behaves like a 5 K offset rather than 278.15 K.
 */
class Quantity(
    val value: Double,
    val dimension: Dimension,
    val tempOffsetK: Double? = null,
    /**
     * True when this is an absolute point in time (a date/datetime), as opposed to a duration.
     * Only meaningful for [Dimension.TIME]: an instant holds epoch seconds in [value]. Instants
     * combine with durations (instant ± duration = instant) and with each other only by
     * subtraction (instant − instant = duration); every other operation is rejected.
     */
    val instant: Boolean = false,
) {
    val isDimensionless: Boolean get() = dimension.isDimensionless
    private val isTemperature: Boolean get() = dimension == Dimension.TEMPERATURE

    operator fun unaryMinus() = Quantity(-value, dimension, tempOffsetK)

    operator fun plus(other: Quantity) = addOrSub(other, 1.0)
    operator fun minus(other: Quantity) = addOrSub(other, -1.0)

    private fun addOrSub(other: Quantity, sign: Double): Quantity {
        if (dimension != other.dimension) {
            throw ExpressionError("Cannot add or subtract incompatible units")
        }
        if (isTemperature) {
            // The right operand always collapses to its degree-size (a delta).
            val rightDelta = other.value
            return if (tempOffsetK != null) {
                // Absolute left: fold into absolute kelvin, then re-normalise (offset 0).
                Quantity((value + tempOffsetK) + sign * rightDelta, Dimension.TEMPERATURE, 0.0)
            } else {
                Quantity(value + sign * rightDelta, Dimension.TEMPERATURE, null)
            }
        }
        if (instant || other.instant) {
            // Both operands are TIME here (the dimension check above passed). Dates are absolute
            // points; they combine with durations, and subtract from each other to give a span.
            return addOrSubInstant(other, sign)
        }
        return Quantity(value + sign * other.value, dimension, null)
    }

    private fun addOrSubInstant(other: Quantity, sign: Double): Quantity {
        if (instant && other.instant) {
            if (sign > 0) throw ExpressionError("Cannot add two dates together")
            return Quantity(value - other.value, Dimension.TIME) // date − date = duration
        }
        if (instant) {
            return Quantity(value + sign * other.value, Dimension.TIME, instant = true) // date ± duration
        }
        if (sign < 0) throw ExpressionError("Cannot subtract a date from a duration")
        return Quantity(value + other.value, Dimension.TIME, instant = true) // duration + date
    }

    operator fun times(other: Quantity): Quantity {
        if (instant || other.instant) throw ExpressionError("A date only supports + or − with a duration")
        // A dimensionless scalar scales the other operand and preserves its temperature offset,
        // which is what turns `20 * celsius` into an absolute 293.15 K.
        if (isDimensionless && tempOffsetK == null) {
            return Quantity(value * other.value, other.dimension, other.tempOffsetK)
        }
        if (other.isDimensionless && other.tempOffsetK == null) {
            return Quantity(value * other.value, dimension, tempOffsetK)
        }
        // Two dimensional operands: combine dimensions; any temperature collapses to a delta.
        return Quantity(value * other.value, dimension * other.dimension, null)
    }

    operator fun div(other: Quantity): Quantity {
        if (instant || other.instant) throw ExpressionError("A date only supports + or − with a duration")
        if (other.isDimensionless && other.tempOffsetK == null) {
            return Quantity(value / other.value, dimension, tempOffsetK)
        }
        return Quantity(value / other.value, dimension / other.dimension, null)
    }

    operator fun rem(other: Quantity): Quantity {
        if (instant || other.instant) throw ExpressionError("A date only supports + or − with a duration")
        if (!other.isDimensionless && dimension != other.dimension) {
            throw ExpressionError("Cannot take the remainder of incompatible units")
        }
        return Quantity(value % other.value, dimension, null)
    }

    fun pow(other: Quantity): Quantity {
        if (instant || other.instant) throw ExpressionError("A date only supports + or − with a duration")
        val n = checkPowExponent(other)
        if (isDimensionless) return Quantity(value.pow(n), Dimension.NONE)
        return Quantity(value.pow(n), dimension.pow(n.toInt()))
    }

    private fun checkPowExponent(other: Quantity): Double {
        if (!other.isDimensionless) throw ExpressionError("Exponent must be dimensionless")
        val n = other.value
        if (n != floor(n)) throw ExpressionError("Cannot raise a unit to a non-integer power")
        return n
    }

    fun abs(): Quantity = Quantity(kotlin.math.abs(value), dimension, tempOffsetK)

    /** Factory for dimensionless [Quantity] values. */
    companion object {
        fun scalar(value: Double) = Quantity(value, Dimension.NONE)
    }
}

/**
 * One unit the user can pick. [token] is the ASCII spelling the parser understands (inserted
 * by the keypad picker, e.g. `degC`, `um`, `ohm`); [symbol] is the pretty display form
 * (`°C`, `µm`, `Ω`); [aliases] are extra spellings the parser also accepts (e.g. `c` for `degC`).
 *
 * [factorToBase] scales a magnitude to coherent base units. [offsetK] is set only for affine
 * temperature units and is the absolute kelvin at zero of the unit (0 for K, 273.15 for °C).
 */
class UnitDef(
    val token: String,
    val symbol: String,
    val name: String,
    val dimension: Dimension,
    val factorToBase: Double,
    val offsetK: Double? = null,
    val aliases: List<String> = emptyList(),
) {
    /** Magnitude in this unit → coherent base value (absolute kelvin for temperatures). */
    fun toBase(magnitude: Double): Double = magnitude * factorToBase + (offsetK ?: 0.0)

    /** Coherent base value → magnitude in this unit. */
    fun fromBase(baseValue: Double): Double = (baseValue - (offsetK ?: 0.0)) / factorToBase
}

/** A named group of units, shown as one tab in the converter. */
class UnitCategory(
    val name: String,
    val units: List<UnitDef>,
    /** Whether these units are also usable inside equations (angle units are converter-only). */
    val inEquations: Boolean = true,
)
