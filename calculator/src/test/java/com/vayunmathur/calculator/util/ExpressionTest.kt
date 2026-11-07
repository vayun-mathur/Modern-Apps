package com.vayunmathur.calculator.util

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExpressionTest {

    private fun eval(
        src: String,
        x: Double = 0.0,
        angle: AngleMode = AngleMode.RADIANS,
        ans: Double = 0.0,
    ): Double = Expression.parse(src).eval(x, angle, ans)

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-9) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "expected $expected but was $actual",
        )
    }

    // ---- precedence and associativity ----

    @Test
    fun multiplicationBindsTighterThanAddition() {
        assertEquals(14.0, eval("2 + 3 * 4"))
        assertEquals(20.0, eval("(2 + 3) * 4"))
    }

    @Test
    fun subtractionIsLeftAssociative() {
        // A right-associative fold would give 2 - (3 - 4) = 3.
        assertEquals(-5.0, eval("2 - 3 - 4"))
    }

    @Test
    fun divisionIsLeftAssociative() {
        assertEquals(2.0, eval("16 / 4 / 2"))
    }

    @Test
    fun exponentiationIsRightAssociative() {
        // Left-associative would be (2^3)^2 = 64.
        assertEquals(512.0, eval("2^3^2"))
    }

    @Test
    fun unaryMinusAppliesToThePowerNotTheBase() {
        // Documented in parsePower: -x^2 means -(x^2).
        assertEquals(-9.0, eval("-3^2"))
        assertEquals(9.0, eval("(-3)^2"))
    }

    @Test
    fun repeatedUnarySignsCollapse() {
        assertEquals(5.0, eval("--5"))
        assertEquals(-5.0, eval("---5"))
        assertEquals(5.0, eval("+5"))
    }

    // ---- implicit multiplication ----

    @Test
    fun implicitMultiplicationAgainstVariablesGroupsAndFunctions() {
        assertEquals(6.0, eval("2x", x = 3.0))
        assertEquals(8.0, eval("2(x+1)", x = 3.0))
        assertClose(0.0, eval("2sin(0)"))
        assertEquals(12.0, eval("3(2)(2)"))
    }

    @Test
    fun implicitMultiplicationDoesNotOutrankExplicitPrecedence() {
        // 1 + 2x with x=3 is 1 + 6, not (1+2)*3.
        assertEquals(7.0, eval("1 + 2x", x = 3.0))
    }

    // ---- numbers ----

    @Test
    fun scientificNotationUsesUppercaseEOnly() {
        assertEquals(1500.0, eval("1.5E3"))
        assertEquals(0.0015, eval("1.5E-3"))
        // Lowercase 'e' is Euler's constant, so "2e" is implicit multiplication.
        assertClose(2 * kotlin.math.E, eval("2e"))
    }

    @Test
    fun trailingEIsNotSwallowedAsAnExponent() {
        // parseNumber rewinds when 'E' is not followed by digits, so "2E" is not the
        // half-parsed number 2e0 — the E falls through to parseIdentifier, which
        // lowercases, making this implicit multiplication by Euler's constant.
        assertClose(2 * kotlin.math.E, eval("2E"))
    }

    @Test
    fun leadingDecimalPointParses() {
        assertEquals(0.5, eval(".5"))
    }

    @Test
    fun whitespaceIsIgnored() {
        assertEquals(7.0, eval("  1   +   2 * 3  "))
    }

    // ---- constants, variable, ans ----

    @Test
    fun constantsResolve() {
        assertClose(PI, eval("pi"))
        assertClose(PI, eval("π"))
        assertClose(2 * PI, eval("tau"))
        assertClose(kotlin.math.E, eval("e"))
        assertClose(1.618033988749, eval("phi"), 1e-9)
    }

    @Test
    fun everyVariableSpellingBindsToTheSameFreeVariable() {
        for (name in listOf("x", "t", "theta", "θ")) {
            assertEquals(4.0, eval(name, x = 4.0), "spelling '$name'")
        }
    }

    @Test
    fun ansIsSuppliedAtEvalTime() {
        assertEquals(21.0, eval("ans + 1", ans = 20.0))
    }

    @Test
    fun aParsedExpressionCanBeEvaluatedRepeatedlyWithDifferentValues() {
        val expr = Expression.parse("x^2")
        assertEquals(0.0, expr.eval(0.0))
        assertEquals(4.0, expr.eval(2.0))
        assertEquals(9.0, expr.eval(3.0))
    }

    // ---- angle mode ----

    @Test
    fun degreeModeConvertsArgumentsAndResults() {
        assertClose(1.0, eval("sin(90)", angle = AngleMode.DEGREES), 1e-12)
        assertClose(90.0, eval("asin(1)", angle = AngleMode.DEGREES), 1e-12)
        assertClose(45.0, eval("atan2(1,1)", angle = AngleMode.DEGREES), 1e-12)
    }

    @Test
    fun radianModeIsTheDefault() {
        assertClose(1.0, eval("sin(pi/2)"), 1e-12)
    }

    @Test
    fun hyperbolicFunctionsIgnoreAngleMode() {
        // sinh takes a real, not an angle, so degrees must not scale it.
        assertEquals(eval("sinh(1)"), eval("sinh(1)", angle = AngleMode.DEGREES))
    }

    // ---- absolute-value bars ----

    @Test
    fun absoluteValueBars() {
        assertEquals(5.0, eval("|-5|"))
        assertEquals(3.0, eval("|x|", x = -3.0))
        assertEquals(10.0, eval("2|-5|"))
    }

    @Test
    fun unterminatedBarIsAnError() {
        assertFailsWith<ExpressionError> { eval("|5") }
    }

    // ---- factorial ----

    @Test
    fun factorialOfSmallIntegersIsExact() {
        assertEquals(1.0, eval("0!"))
        assertEquals(120.0, eval("5!"))
        assertEquals(3628800.0, eval("10!"))
    }

    @Test
    fun factorialOfNegativeIntegersIsNaNBecauseGammaHasPolesThere() {
        assertTrue(eval("(-1)!").isNaN())
    }

    @Test
    fun factorialOfNonIntegersGoesThroughGamma() {
        // (1/2)! = sqrt(pi)/2
        assertClose(kotlin.math.sqrt(PI) / 2, eval("0.5!"), 1e-9)
    }

    // ---- multi-argument functions ----

    @Test
    fun logTakesAnOptionalBase() {
        assertClose(2.0, eval("log(100)"), 1e-12)   // base 10 by default
        assertClose(3.0, eval("log(2,8)"), 1e-12)   // log base 2 of 8
    }

    @Test
    fun combinatoricsAndNumberTheory() {
        assertClose(10.0, eval("ncr(5,2)"), 1e-9)
        assertClose(20.0, eval("npr(5,2)"), 1e-9)
        assertEquals(6.0, eval("gcd(12,18)"))
        assertEquals(36.0, eval("lcm(12,18)"))
        assertEquals(2.0, eval("mod(8,3)"))
        assertClose(3.0, eval("root(3,27)"), 1e-12)
    }

    @Test
    fun oddRootOfNegativeIsReal() {
        // root(3, -8) is -2 (matching cbrt(-8)); pow(1/3) alone gives NaN.
        assertClose(-2.0, eval("root(3,-8)"), 1e-9)
        assertClose(-2.0, eval("cbrt(-8)"), 1e-9)
        assertClose(2.0, eval("root(5,32)"), 1e-9)
        // Even roots of negatives have no real value.
        assertTrue(eval("root(2,-4)").isNaN())
    }

    @Test
    fun maxAndMinAcceptVariableArity() {
        assertEquals(9.0, eval("max(1,9,4)"))
        assertEquals(1.0, eval("min(1,9,4)"))
    }

    @Test
    fun wrongArityIsRejected() {
        assertFailsWith<ExpressionError> { eval("gcd(1)") }
        assertFailsWith<ExpressionError> { eval("sin(1,2)") }
    }

    @Test
    fun emptyMaxAndMinAreExpressionErrorsNotCrashes() {
        // a.max()/a.min() on an empty list throw NoSuchElementException, which
        // callers (preview, graph sampling, widget) do not catch — they only
        // handle ExpressionError. Empty varargs must fail the same way as
        // every other arity error.
        assertFailsWith<ExpressionError> { eval("max()") }
        assertFailsWith<ExpressionError> { eval("min()") }
    }

    // ---- errors ----

    @Test
    fun unbalancedParenthesisIsAnError() {
        assertFailsWith<ExpressionError> { eval("(1+2") }
    }

    @Test
    fun trailingGarbageIsAnError() {
        assertFailsWith<ExpressionError> { eval("1+2)") }
    }

    @Test
    fun emptyInputIsAnError() {
        assertFailsWith<ExpressionError> { eval("") }
    }

    @Test
    fun unknownFunctionIsAnError() {
        assertFailsWith<ExpressionError> { eval("frobnicate(2)") }
    }

    @Test
    fun unknownBareSymbolIsAnError() {
        assertFailsWith<ExpressionError> { eval("y") }
    }

    @Test
    fun danglingOperatorIsAnError() {
        assertFailsWith<ExpressionError> { eval("1+") }
    }

    // ---- IEEE edge cases that must not throw ----

    @Test
    fun divisionByZeroFollowsIeeeRatherThanThrowing() {
        assertEquals(Double.POSITIVE_INFINITY, eval("1/0"))
        assertTrue(eval("0/0").isNaN())
    }

    @Test
    fun sqrtOfNegativeIsNaN() {
        assertTrue(eval("sqrt(-1)").isNaN())
    }
}
