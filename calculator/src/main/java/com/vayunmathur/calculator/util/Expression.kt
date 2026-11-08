package com.vayunmathur.calculator.util

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.acosh
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** Degrees in a half circle — the radian/degree scale factor. */
private const val DEGREES_IN_HALF_CIRCLE = 180.0

/** Radicand of the golden ratio: phi = (1 + sqrt(5)) / 2. */
private const val PHI_RADICAND = 5.0

/** Factor scaling tau to pi: tau = 2 * pi. */
private const val TAU_FACTOR = 2.0

/** Lanczos reflection cutoff and half-offset (gamma parameter g = 7, so g + 1/2). */
private const val LANCZOS_HALF = 0.5

/** Lanczos series parameter g, balancing convergence against coefficient growth. */
private const val LANCZOS_G = 7.0

/** Largest n computed with an exact iterative product; beyond it gamma takes over. */
private const val MAX_EXACT_FACTORIAL = 170.0

/** Symbol chars that can start an implicit-multiplication factor (`2pi`, `2√x`, `2θ`). */
private val IMPLICIT_FACTOR_CHARS = setOf('√', 'π', 'θ')

/** Function names evaluated by [Expression.Call.evalTrig]. */
private val TRIG_FUNCTIONS = setOf("sin", "cos", "tan", "asin", "acos", "atan", "sec", "csc", "cot")

/** Function names evaluated by [Expression.Call.evalHyperbolic]. */
private val HYPERBOLIC_FUNCTIONS = setOf("sinh", "cosh", "tanh", "asinh", "acosh", "atanh")

/** Single-argument function names evaluated by [Expression.Call.evalSingle]. */
private val SINGLE_ARG_FUNCTIONS =
    setOf("sqrt", "√", "cbrt", "exp", "ln", "log2", "floor", "ceil", "round", "sign", "gamma", "fact")

/** Two-argument function names evaluated by [Expression.Call.evalPair]. */
private val PAIR_FUNCTIONS = setOf("root", "mod", "atan2", "ncr", "npr", "gcd", "lcm")

/** Whether trigonometric functions interpret/return angles in degrees or radians. */
enum class AngleMode { RADIANS, DEGREES }

/** Thrown when an expression cannot be tokenised or parsed. */
class ExpressionError(message: String) : Exception(message)

/** Values supplied at evaluation time: the free variable, angle mode, and last answer. */
class EvalContext(val variable: Double, val angle: AngleMode, val ans: Double)

/**
 * A parsed, reusable mathematical expression. Parsing happens once (via [parse]); the
 * tree can be [eval]'d many times with different values of the free variable (`x` for
 * Cartesian graphs, `θ`/`t` for polar), which keeps graphing and analysis cheap.
 *
 * Supported: `+ - * / ^ %`, unary minus, postfix `!` (factorial, via the gamma
 * function), implicit multiplication (`2x`, `3(x+1)`, `2sin(x)`), `|x|` bars,
 * scientific notation (`1.5E3`), the constants `pi`/`π`/`e`/`tau`/`phi`, the free
 * variable (`x`/`t`/`θ`/`theta`), `ans` (previous answer), a large unary-function
 * library, and multi-argument functions: `log(b,x)`, `root(n,x)`, `nCr(n,r)`,
 * `nPr(n,r)`, `mod(a,b)`, `gcd(a,b)`, `lcm(a,b)`, `atan2(y,x)`, `max(…)`, `min(…)`.
 */
class Expression private constructor(private val root: Node) {

    /**
     * Evaluate for a given free-variable value, [angle] mode and [ans], returning the numeric
     * magnitude only. Preserves the graphing path and every dimensionless caller: for a
     * dimensionless expression this is exactly the number, so nothing downstream changes.
     */
    fun eval(variable: Double = 0.0, angle: AngleMode = AngleMode.RADIANS, ans: Double = 0.0): Double =
        evalQuantity(variable, angle, ans).value

    /** Evaluate to a full [Quantity], carrying units through the calculation. */
    fun evalQuantity(variable: Double = 0.0, angle: AngleMode = AngleMode.RADIANS, ans: Double = 0.0): Quantity =
        root.eval(EvalContext(variable, angle, ans))

    companion object {
        fun parse(input: String): Expression = Expression(Parser(input).parse())
    }

    // ---- AST ----

    internal sealed interface Node {
        fun eval(ctx: EvalContext): Quantity
    }

    private class Num(val value: Double) : Node {
        override fun eval(ctx: EvalContext) = Quantity.scalar(value)
    }

    private object Var : Node {
        override fun eval(ctx: EvalContext) = Quantity.scalar(ctx.variable)
    }

    private object Ans : Node {
        override fun eval(ctx: EvalContext) = Quantity.scalar(ctx.ans)
    }

    private class UnitNode(val unit: UnitDef) : Node {
        override fun eval(ctx: EvalContext) = Quantity(unit.factorToBase, unit.dimension, unit.offsetK)
    }

    /** An absolute point in time, written `#<epochSeconds>` (inserted by the date/time pickers). */
    private class InstantLit(val epochSeconds: Double) : Node {
        override fun eval(ctx: EvalContext) = Quantity(epochSeconds, Dimension.TIME, instant = true)
    }

    private class Neg(val operand: Node) : Node {
        override fun eval(ctx: EvalContext) = -operand.eval(ctx)
    }

    private class Fact(val operand: Node) : Node {
        override fun eval(ctx: EvalContext) = Quantity.scalar(factorial(operand.eval(ctx).requireScalar("Factorial")))
    }

    private class Binary(val op: Char, val left: Node, val right: Node) : Node {
        override fun eval(ctx: EvalContext): Quantity {
            val a = left.eval(ctx)
            val b = right.eval(ctx)
            return when (op) {
                '+' -> a + b
                '-' -> a - b
                '*' -> a * b
                '/' -> a / b
                '%' -> a % b
                '^' -> a.pow(b)
                else -> throw ExpressionError("Unknown operator '$op'")
            }
        }
    }

    private class Call(val name: String, val args: List<Node>) : Node {
        override fun eval(ctx: EvalContext): Quantity {
            // `abs` is the one function that keeps its argument's dimension; everything else is
            // defined on plain numbers, so its arguments must be dimensionless.
            if (name == "abs") {
                if (args.size != 1) throw ExpressionError("abs expects 1 argument")
                return args[0].eval(ctx).abs()
            }
            val a = args.map { it.eval(ctx).requireScalar(name) }
            return Quantity.scalar(evaluateScalar(a, ctx.angle))
        }

        /** Dispatch a dimensionless call to its function group. */
        private fun evaluateScalar(a: List<Double>, angle: AngleMode): Double = when {
            name in TRIG_FUNCTIONS -> evalTrig(singleArg(a), angle)
            name in HYPERBOLIC_FUNCTIONS -> evalHyperbolic(singleArg(a))
            name in SINGLE_ARG_FUNCTIONS -> evalSingle(singleArg(a))
            name in PAIR_FUNCTIONS -> evalPair(a, angle)
            name == "log" -> if (a.size == 2) ln(a[1]) / ln(a[0]) else log10(singleArg(a))
            name == "max" -> a.maxOrNull() ?: throw ExpressionError("max expects at least 1 argument")
            name == "min" -> a.minOrNull() ?: throw ExpressionError("min expects at least 1 argument")
            else -> throw ExpressionError("Unknown function '$name'")
        }

        private fun singleArg(a: List<Double>): Double {
            if (a.size != 1) throw ExpressionError("$name expects 1 argument")
            return a[0]
        }

        private fun toRad(v: Double, angle: AngleMode) =
            if (angle == AngleMode.DEGREES) v * PI / DEGREES_IN_HALF_CIRCLE else v

        private fun fromRad(v: Double, angle: AngleMode) =
            if (angle == AngleMode.DEGREES) v * DEGREES_IN_HALF_CIRCLE / PI else v

        private fun evalTrig(x: Double, angle: AngleMode): Double = when (name) {
            "sin" -> sin(toRad(x, angle))
            "cos" -> cos(toRad(x, angle))
            "tan" -> tan(toRad(x, angle))
            "asin" -> fromRad(asin(x), angle)
            "acos" -> fromRad(acos(x), angle)
            "atan" -> fromRad(atan(x), angle)
            "sec" -> 1.0 / cos(toRad(x, angle))
            "csc" -> 1.0 / sin(toRad(x, angle))
            else -> 1.0 / tan(toRad(x, angle)) // "cot"
        }

        private fun evalHyperbolic(x: Double): Double = when (name) {
            "sinh" -> sinh(x)
            "cosh" -> cosh(x)
            "tanh" -> tanh(x)
            "asinh" -> asinh(x)
            "acosh" -> acosh(x)
            else -> atanh(x) // "atanh"
        }

        private fun evalSingle(x: Double): Double = when (name) {
            "sqrt", "√" -> sqrt(x)
            "cbrt" -> cbrt(x)
            "exp" -> exp(x)
            "ln" -> ln(x)
            "log2" -> log2(x)
            "floor" -> floor(x)
            "ceil" -> ceil(x)
            "round" -> round(x)
            "sign" -> sign(x)
            "gamma" -> gamma(x)
            else -> factorial(x) // "fact"
        }

        private fun evalPair(a: List<Double>, angle: AngleMode): Double {
            require2(a)
            return when (name) {
                "root" -> realRoot(a[0], a[1])
                "mod" -> a[0] % a[1]
                "atan2" -> fromRad(atan2(a[0], a[1]), angle)
                "ncr" -> combinations(a[0], a[1])
                "npr" -> permutations(a[0], a[1])
                "gcd" -> gcd(a[0], a[1])
                else -> lcm(a[0], a[1]) // "lcm"
            }
        }

        private fun require2(a: List<Double>) {
            if (a.size != 2) throw ExpressionError("$name expects 2 arguments")
        }
    }

    /**
     * Recursive-descent parser. Precedence (low → high):
     * expr → term (('+'|'-') term)*
     * term → factor (('*'|'/'|'%'|implicit) factor)*
     * factor → ('+'|'-') factor | power
     * power → postfix ('^' factor)?          (right associative)
     * postfix → primary ('!')*                (factorial)
     * primary → number | const | var | ans | func '(' args ')' | '(' expr ')' | '|' expr '|'
     */
    private class Parser(input: String) {
        private val src = input
        private var pos = 0

        /** How many `|…|` pairs enclose the position being parsed. See [parseTerm]. */
        private var barDepth = 0

        fun parse(): Node {
            val node = parseExpr()
            skipSpaces()
            if (pos < src.length) throw ExpressionError("Unexpected '${src[pos]}'")
            return node
        }

        private fun skipSpaces() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? {
            skipSpaces()
            return if (pos < src.length) src[pos] else null
        }

        private fun parseExpr(): Node {
            var left = parseTerm()
            while (true) {
                left = parseExprStep(left) ?: break
            }
            return left
        }

        /** One additive step after [left], or null when the expression ends. */
        private fun parseExprStep(left: Node): Node? {
            val c = peek() ?: return null
            if (c != '+' && c != '-') return null
            pos++
            return Binary(c, left, parseTerm())
        }

        private fun parseTerm(): Node {
            var left = parseFactor()
            while (true) {
                left = parseTermStep(left) ?: break
            }
            return left
        }

        /** One multiplicative step after [left], or null when the term ends. */
        private fun parseTermStep(left: Node): Node? {
            val c = peek() ?: return null
            // '|' is ambiguous: it both opens and closes. Inside a pair of bars the
            // next '|' is the closing one, so stop and let parsePrimary consume it.
            // Treating it as the start of another factor made every |x| expression
            // recurse into an unterminated bar and throw.
            if (c == '|' && barDepth > 0) return null
            val op = when (c) {
                '*', '/', '%' -> { pos++; c }
                // Implicit multiplication: value directly followed by a group/name.
                '(', '|' -> '*'
                else -> if (isImplicitFactorStart(c)) '*' else return null
            }
            return Binary(op, left, parseFactor())
        }

        /** Whether [c] can start an implicit-multiplication factor (`2x`, `2√x`, `2θ`). */
        private fun isImplicitFactorStart(c: Char) = c.isLetter() || c in IMPLICIT_FACTOR_CHARS

        private fun parseFactor(): Node {
            val c = peek()
            if (c == '+') { pos++; return parseFactor() }
            if (c == '-') { pos++; return Neg(parseFactor()) }
            return parsePower()
        }

        private fun parsePower(): Node {
            val base = parsePostfix()
            if (peek() == '^') {
                pos++
                return Binary('^', base, parseFactor()) // right associative; -x^2 = -(x^2)
            }
            return base
        }

        private fun parsePostfix(): Node {
            var node = parsePrimary()
            while (peek() == '!') { pos++; node = Fact(node) }
            return node
        }

        private fun parsePrimary(): Node {
            val c = peek() ?: throw ExpressionError("Unexpected end of expression")
            when {
                c == '(' -> return parseParenGroup()
                c == '|' -> return parseBarGroup()
                c.isDigit() || c == '.' -> return parseNumber()
                c == '#' -> return parseInstant()
                c.isLetter() || c in IMPLICIT_FACTOR_CHARS -> return parseIdentifier()
                else -> throw ExpressionError("Unexpected '$c'")
            }
        }

        /** Parse `(…)`, the opening paren already peeked but not consumed. */
        private fun parseParenGroup(): Node {
            pos++
            val inner = parseExpr()
            if (peek() != ')') throw ExpressionError("Missing ')'")
            pos++
            return inner
        }

        /** Parse `|…|`, the opening bar already peeked but not consumed. */
        private fun parseBarGroup(): Node {
            pos++
            barDepth++
            val inner = parseExpr()
            if (peek() != '|') throw ExpressionError("Missing '|'")
            pos++
            barDepth--
            return Call("abs", listOf(inner))
        }

        /** Parse a `#<epochSeconds>` date literal (the `#` has been peeked, not consumed). */
        private fun parseInstant(): Node {
            pos++ // consume '#'
            val start = pos
            if (pos < src.length && src[pos] == '-') pos++
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            val text = src.substring(start, pos)
            val value = text.toDoubleOrNull() ?: throw ExpressionError("Invalid date literal '$text'")
            return InstantLit(value)
        }

        private fun parseNumber(): Node {
            skipSpaces()
            val start = pos
            consumeMantissa()
            consumeExponent()
            val text = src.substring(start, pos)
            val value = text.toDoubleOrNull() ?: throw ExpressionError("Invalid number '$text'")
            return Num(value)
        }

        /** Integer digits plus at most one fraction part. */
        private fun consumeMantissa() {
            consumeDigits()
            if (pos < src.length && src[pos] == '.') {
                pos++
                consumeDigits()
            }
        }

        private fun consumeDigits() {
            while (pos < src.length && src[pos].isDigit()) pos++
        }

        /** Uppercase-'E' scientific exponent; lowercase 'e' is Euler's constant. */
        private fun consumeExponent() {
            if (pos >= src.length || src[pos] != 'E') return
            val save = pos
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            if (pos < src.length && src[pos].isDigit()) {
                consumeDigits()
            } else {
                pos = save // not an exponent after all
            }
        }

        private fun parseIdentifier(): Node {
            skipSpaces()
            if (src[pos] == '√') { pos++; return Call("sqrt", listOf(parseFactor())) }
            if (src[pos] == 'π') { pos++; return Num(PI) }
            if (src[pos] == 'θ') { pos++; return Var }
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_')) pos++
            val raw = src.substring(start, pos)
            val name = raw.lowercase()
            return when (name) {
                "x", "t", "theta" -> Var
                "ans" -> Ans
                "pi" -> Num(PI)
                "tau" -> Num(TAU_FACTOR * PI)
                "e" -> Num(E)
                "phi" -> Num((1 + sqrt(PHI_RADICAND)) / 2)
                else -> parseFunctionOrUnit(raw, name)
            }
        }

        /** A trailing name is a function call, or a unit from the registry (case-sensitive). */
        private fun parseFunctionOrUnit(raw: String, name: String): Node {
            if (peek() != '(') {
                // Not a function call: try the unit registry (case-sensitively, so `mm`
                // and `Mm` differ) before giving up.
                val unit = UnitRegistry.parseTokens[raw]
                if (unit != null) return UnitNode(unit)
                throw ExpressionError("Unknown symbol '$raw'")
            }
            pos++
            val args = parseArgs()
            if (peek() != ')') throw ExpressionError("Missing ')' after $name")
            pos++
            return Call(name, args)
        }

        private fun parseArgs(): List<Node> {
            val args = mutableListOf<Node>()
            if (peek() == ')') return args
            args.add(parseExpr())
            while (peek() == ',') { pos++; args.add(parseExpr()) }
            return args
        }
    }
}

// ---- Special functions shared by the AST ----

/** Unwrap a [Quantity] that must be dimensionless, or fail with a clear message. */
private fun Quantity.requireScalar(context: String): Double {
    if (!isDimensionless) throw ExpressionError("$context requires a dimensionless value")
    return value
}

/** Lanczos approximation of the gamma function (valid across the reals except poles). */
private fun gamma(x: Double): Double {
    if (x < LANCZOS_HALF) return PI / (sin(PI * x) * gamma(1 - x))
    val z = x - 1
    var a = LANCZOS_COEFFICIENTS[0]
    val t = z + LANCZOS_G + LANCZOS_HALF
    for (i in 1 until LANCZOS_COEFFICIENTS.size) a += LANCZOS_COEFFICIENTS[i] / (z + i)
    return sqrt(2 * PI) * t.pow(z + LANCZOS_HALF) * exp(-t) * a
}

/** Lanczos series coefficients for g = 7 (nine-term expansion). */
private val LANCZOS_COEFFICIENTS = doubleArrayOf(
    0.99999999999980993, 676.5203681218851, -1259.1392167224028,
    771.32342877765313, -176.61502916214059, 12.507343278686905,
    -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7,
)

/** Smallest factor in the exact iterative factorial product. */
private const val FACTORIAL_FIRST_FACTOR = 2.0

/** Factorial via gamma so non-integers work too; exact for small non-negative integers. */
private fun factorial(n: Double): Double {
    if (n < 0 && n == floor(n)) return Double.NaN // poles at negative integers
    if (n == floor(n) && n <= MAX_EXACT_FACTORIAL) return exactFactorial(n.toInt())
    return gamma(n + 1)
}

/** Exact integer product `1·2·…·n` for small n. */
private fun exactFactorial(n: Int): Double {
    var result = 1.0
    var i = FACTORIAL_FIRST_FACTOR.toInt()
    while (i <= n) {
        result *= i
        i++
    }
    return result
}

/**
 * The real [n]th root of [x]. `x.pow(1/n)` is NaN for every negative [x], but an
 * odd-integer root of a negative number is real (root(3, -8) = -2, exactly what
 * `cbrt` already returns); even and non-integer roots of negatives stay NaN.
 */
private fun realRoot(n: Double, x: Double): Double =
    if (x < 0 && n == floor(n) && n % 2 != 0.0) -((-x).pow(1.0 / n)) else x.pow(1.0 / n)

private fun combinations(n: Double, r: Double): Double =
    factorial(n) / (factorial(r) * factorial(n - r))

private fun permutations(n: Double, r: Double): Double =
    factorial(n) / factorial(n - r)

private fun gcd(a: Double, b: Double): Double {
    var x = abs(round(a)).toLong()
    var y = abs(round(b)).toLong()
    while (y != 0L) { val t = y; y = x % y; x = t }
    return x.toDouble()
}

private fun lcm(a: Double, b: Double): Double {
    val g = gcd(a, b)
    return if (g == 0.0) 0.0 else abs(round(a) * round(b)) / g
}
