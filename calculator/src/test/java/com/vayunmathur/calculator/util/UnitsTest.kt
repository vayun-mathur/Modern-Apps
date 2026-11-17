package com.vayunmathur.calculator.util

import com.vayunmathur.calculator.domain.*
import com.vayunmathur.calculator.network.*
import com.vayunmathur.calculator.platform.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UnitsTest {

    private fun q(src: String, x: Double = 0.0, angle: AngleMode = AngleMode.RADIANS): Quantity =
        Expression.parse(src).evalQuantity(x, angle)

    private fun unit(token: String): UnitDef =
        UnitRegistry.parseTokens[token] ?: error("no unit '$token'")

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-6) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "expected $expected but was $actual",
        )
    }

    // ---- dimension algebra ----

    @Test
    fun derivedDimensionsComposeFromBaseDimensions() {
        assertEquals(Dimension.AREA, Dimension.LENGTH * Dimension.LENGTH)
        assertEquals(Dimension.VOLUME, Dimension.LENGTH.pow(3))
        assertEquals(Dimension.SPEED, Dimension.LENGTH / Dimension.TIME)
        assertEquals(Dimension.NONE, Dimension.LENGTH / Dimension.LENGTH)
        assertTrue(Dimension.NONE.isDimensionless)
        assertTrue((Dimension.LENGTH / Dimension.LENGTH).isDimensionless)
    }

    @Test
    fun dimensionEqualityIgnoresConstructionOrder() {
        val force1 = Dimension.MASS * Dimension.LENGTH / Dimension.TIME.pow(2)
        val force2 = Dimension.LENGTH / Dimension.TIME * Dimension.MASS / Dimension.TIME
        assertEquals(force1, force2)
        assertEquals(Dimension.FORCE, force1)
    }

    // ---- linear conversions ----

    @Test
    fun addingLengthsInDifferentUnitsSharesADimension() {
        val r = q("5m + 2ft")
        assertEquals(Dimension.LENGTH, r.dimension)
        assertClose(5.6096, r.value)                      // base metres
        assertClose(560.96, unit("cm").fromBase(r.value)) // shown in cm
    }

    @Test
    fun massConversion() {
        assertClose(1.0, unit("lb").fromBase(q("0.45359237kg").value))
    }

    @Test
    fun timeConversion() {
        assertEquals(Dimension.TIME, q("1h").dimension)
        assertClose(3600.0, q("1h").value)
    }

    @Test
    fun lightUnitsConvertBySpeedOfLight() {
        assertEquals(Dimension.LENGTH, q("1ly").dimension)
        assertClose(9.4607304725808e15, q("1ly").value, tolerance = 1e3)
        assertClose(299792458.0, q("1ls").value, tolerance = 1e-3)
        assertClose(0.299792458, q("1lns").value, tolerance = 1e-12)
        // Aliases resolve to the same units.
        assertClose(q("1ly").value, q("1lightyear").value, tolerance = 1.0)
        assertClose(1.0, unit("ly").fromBase(q("1lightyear").value))
        assertClose(9.4607304725808e12, unit("km").fromBase(q("1ly").value), tolerance = 1e3)
        assertTrue(UnitRegistry.unitsFor(Dimension.LENGTH).any { it.token == "ly" })
    }

    @Test
    fun astronomicalUnitsAndParsecs() {
        assertEquals(Dimension.LENGTH, q("1AU").dimension)
        assertClose(1.495978707e11, q("1AU").value, tolerance = 1.0)
        assertClose(q("1AU").value, q("1au").value, tolerance = 1.0)
        assertEquals(Dimension.LENGTH, q("1pc").dimension)
        assertClose(3.08567758149137e16, q("1pc").value, tolerance = 1e4)
        assertClose(3.26156, unit("ly").fromBase(q("1pc").value), tolerance = 1e-4)
        assertClose(1000.0, unit("pc").fromBase(q("1kpc").value), tolerance = 1e-9)
        assertClose(1.0, unit("pc").fromBase(q("1parsec").value), tolerance = 1e-12)
        assertClose(206264.806, unit("AU").fromBase(q("1pc").value), tolerance = 1e-3)
        assertTrue(UnitRegistry.unitsFor(Dimension.LENGTH).any { it.token == "AU" })
        assertTrue(UnitRegistry.unitsFor(Dimension.LENGTH).any { it.token == "pc" })
    }

    @Test
    fun areaVolumeSpeedDataEnergyPressureForceSamples() {
        assertEquals(Dimension.AREA, q("5m^2").dimension)
        assertClose(5.0, q("5m^2").value)

        assertEquals(Dimension.VOLUME, q("2L").dimension)
        assertClose(0.002, q("2L").value)

        assertEquals(Dimension.SPEED, q("36kmh").dimension)
        assertClose(10.0, q("36kmh").value)

        assertEquals(Dimension.INFORMATION, q("1B").dimension)
        assertClose(8.0, q("1B").value)                       // one byte = 8 bits
        assertClose(1024.0, unit("B").fromBase(q("1KiB").value))

        assertEquals(Dimension.ENERGY, q("1kWh").dimension)
        assertClose(3.6e6, q("1kWh").value)

        assertEquals(Dimension.PRESSURE, q("1atm").dimension)
        assertClose(101325.0, q("1atm").value)

        assertEquals(Dimension.FORCE, q("1N").dimension)
        assertClose(1.0, q("1N").value)
    }

    @Test
    fun multiplyingUnitsProducesADerivedDimension() {
        // force × length = energy
        val r = q("2N * 3m")
        assertEquals(Dimension.ENERGY, r.dimension)
        assertClose(6.0, r.value)
    }

    // ---- temperature ----

    @Test
    fun standaloneTemperatureIsAbsolute() {
        val r = q("20c")
        assertEquals(Dimension.TEMPERATURE, r.dimension)
        assertTrue(r.tempOffsetK != null, "standalone temperature should be absolute")
        assertClose(293.15, displayValueIn(r, unit("K")))
        assertClose(68.0, displayValueIn(r, unit("degF")))
    }

    @Test
    fun firstTemperatureIsAbsoluteAndTheRestAreDeltas() {
        // The user's rule: 200k - 5c -> 195K.
        val r = q("200k - 5c")
        assertEquals(Dimension.TEMPERATURE, r.dimension)
        assertTrue(r.tempOffsetK != null)
        assertClose(195.0, displayValueIn(r, unit("K")))
    }

    @Test
    fun addingTwoCelsiusValuesAddsDegreeSizes() {
        val r = q("5c + 3c")
        assertClose(8.0, displayValueIn(r, unit("degC")))
    }

    // ---- error cases ----

    @Test
    fun addingIncompatibleUnitsFails() {
        assertFailsWith<ExpressionError> { q("1m + 1kg") }
    }

    @Test
    fun transcendentalFunctionsRejectDimensionalArguments() {
        assertFailsWith<ExpressionError> { q("sin(1m)") }
        assertFailsWith<ExpressionError> { q("ln(2kg)") }
    }

    @Test
    fun nonIntegerPowerOfADimensionalBaseFails() {
        assertFailsWith<ExpressionError> { q("m^0.5") }
    }

    @Test
    fun absKeepsItsDimension() {
        val r = q("|(-3)m|")
        assertEquals(Dimension.LENGTH, r.dimension)
        assertClose(3.0, r.value)
    }

    // ---- parser disambiguation ----

    @Test
    fun functionCallsAndUnitsWithTheSameNameCoexist() {
        // min(...) is the function; 5min is five minutes.
        assertClose(2.0, q("min(2,3)").value)
        assertTrue(q("min(2,3)").isDimensionless)

        val fiveMinutes = q("5min")
        assertEquals(Dimension.TIME, fiveMinutes.dimension)
        assertClose(300.0, fiveMinutes.value)
        assertClose(5.0, unit("min").fromBase(fiveMinutes.value))
    }

    @Test
    fun lowercaseEIsStillEulersConstant() {
        assertClose(2 * kotlin.math.E, q("2e").value)
        assertTrue(q("2e").isDimensionless)
    }

    @Test
    fun xIsStillTheFreeVariable() {
        assertClose(4.0, q("x", x = 4.0).value)
        assertTrue(q("x", x = 4.0).isDimensionless)
    }

    @Test
    fun caseDistinguishesUnits() {
        // Lowercase c is Celsius (temperature); uppercase C is coulomb (charge).
        assertEquals(Dimension.TEMPERATURE, q("5c").dimension)
        assertEquals(Dimension.CHARGE, q("5C").dimension)
        // Lowercase mm is millimetre; the two never collide because lookup is case-sensitive.
        assertClose(0.001, q("1mm").value)
    }

    // ---- converter path ----

    @Test
    fun converterAffineTemperatureRoundTrips() {
        val c = unit("degC")
        val f = unit("degF")
        // 100 °C -> 212 °F
        assertClose(212.0, f.fromBase(c.toBase(100.0)))
        // and back
        assertClose(100.0, c.fromBase(f.toBase(212.0)))
    }

    @Test
    fun formatQuantityAppendsSymbolAndTokenForms() {
        val r = q("5m + 2ft")
        assertEquals("560.96\u202Fcm", formatQuantity(r, unit("cm")))
        assertEquals("560.96 cm", formatQuantity(r, unit("cm"), useToken = true))
        assertEquals("5", formatQuantity(Quantity.scalar(5.0), null))
    }
}
