package com.vayunmathur.calculator.util

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExpressionExtraTest {

    private fun eval(src: String, angle: AngleMode = AngleMode.RADIANS): Double =
        Expression.parse(src).eval(angle = angle)

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    @Test fun reciprocalTrigFunctions() {
        assertClose(1.0, eval("sec(0)"))
        assertClose(1.0, eval("csc(${PI / 2})"))
        assertClose(0.0, eval("cot(${PI / 2})"))
        assertClose(1.0, eval("sec(0)", AngleMode.DEGREES))
    }

    @Test fun inverseTrigInDegrees() {
        assertClose(90.0, eval("asin(1)", AngleMode.DEGREES))
        assertClose(0.0, eval("acos(1)", AngleMode.DEGREES))
        assertClose(45.0, eval("atan(1)", AngleMode.DEGREES))
        assertClose(PI / 2, eval("asin(1)"))
    }

    @Test fun hyperbolicFunctions() {
        assertClose(0.0, eval("sinh(0)"))
        assertClose(1.0, eval("cosh(0)"))
        assertClose(0.0, eval("tanh(0)"))
        assertClose(0.0, eval("asinh(0)"))
        assertClose(0.0, eval("acosh(1)"))
        assertClose(0.0, eval("atanh(0)"))
    }

    @Test fun singleArgFunctions() {
        assertClose(3.0, eval("cbrt(27)"))
        assertClose(1.0, eval("exp(0)"))
        assertClose(3.0, eval("log2(8)"))
        assertClose(2.0, eval("floor(2.7)"))
        assertClose(3.0, eval("ceil(2.1)"))
        assertClose(3.0, eval("round(2.6)"))
        assertClose(-1.0, eval("sign(-42)"))
        assertClose(120.0, eval("fact(5)"))
        assertClose(2.0, eval("√(4)"))
    }

    @Test fun pairFunctions() {
        assertClose(3.0, eval("root(3,27)"))
        assertClose(1.0, eval("mod(10,3)"))
        assertClose(45.0, eval("atan2(1,1)", AngleMode.DEGREES))
        assertClose(12.0, eval("gcd(36,24)") + 0.0)
        assertClose(72.0, eval("lcm(24,36)"))
        assertClose(10.0, eval("ncr(5,2)"))
        assertClose(20.0, eval("npr(5,2)"))
    }

    @Test fun quantityRemainderAndDivision() {
        val rem = Expression.parse("10m % (3m)").evalQuantity()
        assertClose(1.0, rem.value)
        assertEquals(Dimension.LENGTH, rem.dimension)
        val div = Expression.parse("10m / (4s)").evalQuantity()
        assertEquals(Dimension.SPEED, div.dimension)
        assertClose(2.5, div.value)
        val scaled = Expression.parse("6km / 2").evalQuantity()
        assertClose(3000.0, scaled.value)
    }

    @Test fun incompatibleRemainderIsRejected() {
        assertFailsWith<ExpressionError> {
            Expression.parse("10m % (3s)").evalQuantity()
        }
    }

    @Test fun dimensionalExponentIsRejected() {
        assertFailsWith<ExpressionError> {
            Expression.parse("10m ^ (2s)").evalQuantity()
        }
    }

    @Test fun dimensionToString() {
        assertEquals("1", Dimension.NONE.toString())
        assertTrue(Dimension.LENGTH.toString().contains("LENGTH"))
        assertTrue(Dimension.SPEED.toString().contains("TIME"))
    }

    @Test fun graphTapWithNoViewSizeIsSafe() {
        val tab = GraphTab()
        // No view size reported yet: sampling yields no curves, no marker.
        tab.tapGraph(GraphPoint(0.0, 0.0), 1.0)
        assertEquals(0, tab.markers.size)
    }

    @Test fun converterDropdownLambdaSelectsUnit() {
        // Covered at the screen level elsewhere; here the registry pair
        // conversion used by the widget and dropdowns is sanity-checked.
        val length = UnitRegistry.categories[0]
        val metre = length.units.first { it.token == "m" }
        val cm = length.units.first { it.token == "cm" }
        assertClose(100.0, cm.fromBase(metre.toBase(1.0)))
    }
}
