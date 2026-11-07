package com.vayunmathur.calculator.util

import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

/** Significant digits kept when rounding away binary-floating-point artefacts. */
private const val SIGNIFICANT_DIGITS = 12

/** Magnitudes at or above this use scientific notation. */
private const val SCIENTIFIC_UPPER = 1e12

/** Magnitudes below this use scientific notation. */
private const val SCIENTIFIC_LOWER = 1e-6

/**
 * Formats a computed [Double] for display: trims floating-point noise, uses plain
 * decimal notation for "normal" magnitudes and scientific notation for very large or
 * very small values, and reports non-finite results as human-readable errors.
 */
fun formatResult(value: Double): String {
    if (value.isNaN()) return "Undefined"
    if (value.isInfinite()) return if (value > 0) "∞" else "-∞"
    if (value == 0.0) return "0"

    val magnitude = abs(value)
    // Round to hide binary-floating-point artefacts (e.g. 0.1 + 0.2 → 0.30000000000000004).
    val rounded = BigDecimal(value).round(MathContext(SIGNIFICANT_DIGITS)).stripTrailingZeros()

    return if (magnitude >= SCIENTIFIC_UPPER || magnitude < SCIENTIFIC_LOWER) {
        // Scientific notation, e.g. 1.23e+15.
        rounded.toString().replace("E", "e")
    } else {
        rounded.toPlainString()
    }
}

/**
 * [formatResult] rewritten so the string re-parses as an expression: the parser's
 * scientific notation is uppercase `E` (lowercase `e` is Euler's constant, so the
 * display form `1e+12` re-parses as `1*e+12` = 14.718…). Non-finite values have no
 * re-parsable form and keep their display spelling.
 */
fun formatResultToken(value: Double): String =
    if (value.isFinite()) formatResult(value).replace('e', 'E') else formatResult(value)

/** The magnitude of [q] expressed in [unit] (handling affine temperature and deltas). */
fun displayValueIn(q: Quantity, unit: UnitDef): Double =
    if (q.dimension == Dimension.TEMPERATURE) {
        if (q.tempOffsetK != null) unit.fromBase(q.value + q.tempOffsetK) // absolute
        else q.value / unit.factorToBase // delta: degree-size only, no offset
    } else {
        unit.fromBase(q.value)
    }

/**
 * Formats a [Quantity] for display, converting it into [unit] and appending a label. A
 * dimensionless quantity (or a null [unit]) is just its number. When [useToken] is set the
 * ASCII parse token and a plain space are used so the string re-parses (for result round-trips);
 * otherwise the pretty [UnitDef.symbol] and a thin space are used for display.
 */
fun formatQuantity(q: Quantity, unit: UnitDef?, useToken: Boolean = false): String {
    if (q.instant) return formatInstant(q.value, useToken)
    if (q.isDimensionless || unit == null) {
        return if (useToken) formatResultToken(q.value) else formatResult(q.value)
    }
    val label = if (useToken) unit.token else unit.symbol
    val separator = if (useToken) " " else "\u202F"
    val number = if (useToken) formatResultToken(displayValueIn(q, unit)) else formatResult(displayValueIn(q, unit))
    return number + separator + label
}

/**
 * Renders an absolute instant (epoch seconds). [useToken] gives the re-parsable `#<epoch>` form;
 * otherwise a localized string — a date alone at midnight, or a date-and-time otherwise. The
 * midnight check is what makes `date + duration` show a time only once one is actually present.
 */
fun formatInstant(epochSeconds: Double, useToken: Boolean = false): String {
    if (useToken) return "#" + epochSeconds.toLong()
    val zoned = Instant.ofEpochSecond(epochSeconds.toLong()).atZone(ZoneId.systemDefault())
    val atMidnight = zoned.hour == 0 && zoned.minute == 0 && zoned.second == 0
    val formatter = if (atMidnight) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    } else {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    }
    return zoned.format(formatter)
}

private val INSTANT_TOKEN = Regex("#(-?\\d+)")

/** Replaces raw `#<epoch>` date tokens in an expression string with their localized text, for
 * showing the input line. Non-date parts are left untouched. */
fun renderInputForDisplay(input: String): String =
    INSTANT_TOKEN.replace(input) { formatInstant(it.groupValues[1].toDouble()) }
