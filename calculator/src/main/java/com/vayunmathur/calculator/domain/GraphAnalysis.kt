package com.vayunmathur.calculator.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A point in graph (not screen) coordinates. */
data class GraphPoint(val x: Double, val y: Double)

/** The kinds of notable point the grapher can find. */
enum class FeatureKind {
    ROOT,
    Y_INTERCEPT,
    MINIMUM,
    MAXIMUM,
    INTERSECTION,
}

/**
 * A notable point on — or between — curves. [curveIds] holds the one curve the feature
 * belongs to, or the two curves that cross at an [FeatureKind.INTERSECTION].
 */
data class GraphFeature(
    val point: GraphPoint,
    val kind: FeatureKind,
    val curveIds: List<Long>,
)

/**
 * A curve reduced to connected polyline runs in graph space. A new run begins wherever the
 * curve is undefined or jumps (an asymptote), so runs never bridge a discontinuity — which
 * is what stops `tan(x)` from appearing to cross every horizontal line at its asymptotes.
 */
class SampledCurve(
    val id: Long,
    val expr: Expression,
    val polar: Boolean,
    val runs: List<List<GraphPoint>>,
)

/**
 * Numeric analysis of the graphed curves: roots, axis intercepts, local extrema, and
 * intersections. Everything is derived from the same polyline sampling used to draw the
 * curves, so a marker always sits on the line the user can see, and so Cartesian and polar
 * curves — including intersections *between* the two kinds — are handled uniformly.
 *
 * Exact values are recovered afterwards by bisection / ternary search wherever the curve is
 * Cartesian; polar features keep their (sub-pixel) polyline estimate.
 */
object GraphAnalysis {

    /** Turns of θ a polar curve is swept for when it never starts retracing itself. */
    private const val POLAR_TURNS = 12

    /** θ values probed when testing whether a polar curve repeats after a whole number of turns. */
    private const val PERIOD_PROBES = 64

    /** Relative agreement required of every probe before a curve counts as repeating. */
    private const val PERIOD_TOLERANCE = 1e-9

    /** Probe spacing, in turns — irrational, so a curve can't resonate with the probe grid. */
    private const val GOLDEN_RATIO = 0.6180339887498949

    /** Target on-screen gap between consecutive polar samples, in pixels. */
    private const val POLAR_CHORD_PX = 1.0

    /** Coarsest θ step, however little the curve is doing. */
    private const val POLAR_MAX_STEP = 2 * PI / 180

    /** How much each polar step reaches beyond the one before, before being halved back. */
    private const val POLAR_STEP_GROWTH = 1.6

    /** Ceiling on polar samples per curve, so a long sweep can't stall a frame. */
    private const val POLAR_SAMPLE_BUDGET = 20000

    /** How far past the visible radius `r` must swing for a sign flip to count as a pole. */
    private const val POLE_FACTOR = 8.0

    private const val REFINE_ITERS = 80

    private fun evalOrNaN(e: Expression, v: Double, angle: AngleMode): Double =
        try { e.eval(v, angle) } catch (ignored: ExpressionError) { Double.NaN }

    // ---- Sampling ----

    /**
     * Samples one curve across the visible window. [columns] is normally the canvas width in
     * pixels, giving one Cartesian sample per pixel column; polar curves use it to work out
     * the pixels-per-unit of the viewport, and sweep θ over as many turns as they need
     * (polar angles are always radians, matching how they are drawn).
     */
    fun sample(
        id: Long,
        expr: Expression,
        polar: Boolean,
        angle: AngleMode,
        xMin: Double,
        xMax: Double,
        yMin: Double,
        yMax: Double,
        columns: Int,
    ): SampledCurve {
        val runs = ArrayList<List<GraphPoint>>()
        var current = ArrayList<GraphPoint>()
        // Treat a vertical leap of more than a few screens as a discontinuity, not a segment.
        val jumpLimit = (yMax - yMin) * 4

        fun breakRun() {
            if (current.size > 1) runs.add(current)
            current = ArrayList()
        }

        if (polar) {
            PolarScan(expr, xMin, xMax, yMin, yMax, columns).scan(current, ::breakRun)
        } else {
            val steps = columns.coerceAtLeast(2)
            var i = 0
            var prevY = Double.NaN
            while (i <= steps) {
                val x = xMin + (xMax - xMin) * i / steps
                val y = evalOrNaN(expr, x, angle)
                if (y.isFinite()) {
                    if (prevY.isFinite() && abs(y - prevY) > jumpLimit) breakRun()
                    current.add(GraphPoint(x, y))
                } else {
                    breakRun()
                }
                prevY = y
                i++
            }
        }
        breakRun()
        return SampledCurve(id, expr, polar, runs)
    }

    /** One halved-until-short polar step: the probe that survived refinement, and its step. */
    private class PolarProbe(val theta: Double, val r: Double, val point: GraphPoint?)

    /** The outcome of refining one polar step: where it landed and the step it kept. */
    private class PolarAdvance(val theta: Double, val r: Double, val point: GraphPoint?, val step: Double)

    /**
     * Polar sampling, driven by the viewport rather than by a fixed count: each step reaches
     * a little further than the last, then halves until the segment it actually produced is
     * short enough on screen. Measuring the segment rather than predicting it from a
     * derivative is what keeps a curve smooth where it sweeps through the origin — a rose's
     * radius is tiny there but its direction is turning fastest — and it lets the sweep stride
     * across the empty stretches where the curve has swung out of view. Twelve turns then cost
     * little enough that spirals and other curves that never close no longer stop after one loop.
     */
    private class PolarScan(
        private val expr: Expression,
        xMin: Double,
        xMax: Double,
        yMin: Double,
        yMax: Double,
        columns: Int,
    ) {
        private val pxPerUnit = if (xMax > xMin) columns.coerceAtLeast(2) / (xMax - xMin) else 1.0

        // Nothing further from the origin than the farthest corner can be on screen.
        private val visibleRadius = maxOf(
            hypot(xMin, yMin), hypot(xMin, yMax), hypot(xMax, yMin), hypot(xMax, yMax),
        )
        private val visibleRadiusPx = (visibleRadius * pxPerUnit).coerceAtLeast(1.0)
        private val thetaMax = 2 * PI * polarTurns(expr)

        // Detail has to stop somewhere, or a tight spiral would sample without end.
        private val budgetStep = thetaMax / POLAR_SAMPLE_BUDGET

        fun scan(current: MutableList<GraphPoint>, breakRun: () -> Unit) {
            var theta = 0.0
            var r = evalOrNaN(expr, 0.0, AngleMode.RADIANS)
            var here = if (r.isFinite()) GraphPoint(r, 0.0) else null
            here?.let { current.add(it) }
            var step = POLAR_MAX_STEP
            while (theta < thetaMax) {
                step = (step * POLAR_STEP_GROWTH).coerceAtMost(POLAR_MAX_STEP)
                val advance = refineStep(theta, step, here, r)
                step = advance.step
                val to = advance.point
                if (to == null) {
                    breakRun()
                } else {
                    // A pole must not be bridged, exactly as for a Cartesian asymptote.
                    if (crossesPole(r, advance.r, visibleRadius)) breakRun()
                    current.add(to)
                }
                theta = advance.theta
                r = advance.r
                here = to
            }
        }

        /** Probe first, then halve until the segment is short enough on screen. */
        private fun refineStep(theta: Double, step: Double, here: GraphPoint?, r: Double): PolarAdvance {
            var refined = step
            var probe = probe(theta, refined)
            while (segmentTooLong(here, probe.point, probe.r, r, refined)) {
                refined /= 2
                probe = probe(theta, refined)
            }
            return PolarAdvance(probe.theta, probe.r, probe.point, refined)
        }

        /** Whether the segment still needs halving: it never does once detail bottoms out. */
        private fun segmentTooLong(
            here: GraphPoint?,
            to: GraphPoint?,
            nextR: Double,
            r: Double,
            step: Double,
        ): Boolean {
            val from = here ?: return false
            val target = to ?: return false
            if (step <= 2 * budgetStep) return false
            val tolerance = tolerancePx(minOf(abs(r), abs(nextR)) * pxPerUnit)
            return hypot(target.x - from.x, target.y - from.y) * pxPerUnit > tolerance
        }

        /** How far apart two samples may land: a pixel on screen, looser further out. */
        private fun tolerancePx(radiusPx: Double) =
            POLAR_CHORD_PX + (radiusPx - visibleRadiusPx).coerceAtLeast(0.0) / 2

        private fun probe(theta: Double, step: Double): PolarProbe {
            val nextTheta = (theta + step).coerceAtMost(thetaMax)
            val nextR = evalOrNaN(expr, nextTheta, AngleMode.RADIANS)
            val next = if (nextR.isFinite()) {
                GraphPoint(nextR * cos(nextTheta), nextR * sin(nextTheta))
            } else {
                null
            }
            return PolarProbe(nextTheta, nextR, next)
        }
    }

    /**
     * Whether `r` flipped sign between two samples by running out towards infinity rather
     * than by passing through zero — a polar asymptote, as in `r = tan(θ)`, which would
     * otherwise be drawn as a line sweeping right across the screen. Both magnitudes have to
     * dwarf anything that could be on screen, so the zero crossing at the base of every rose
     * petal is never mistaken for one.
     */
    private fun crossesPole(prev: Double, current: Double, visibleRadius: Double): Boolean {
        if (!prev.isFinite() || !current.isFinite()) return false
        if ((prev < 0.0) == (current < 0.0)) return false
        return minOf(abs(prev), abs(current)) > POLE_FACTOR * visibleRadius
    }

    /**
     * How many turns of θ a polar curve covers before it starts retracing itself, capped at
     * [POLAR_TURNS]. A rose, circle or cardioid closes after one turn and is swept once —
     * sweeping it twelve times would only lay the same ink down twelve times over. Curves
     * that never close (`r = θ`, `r = cos(√2·θ)`, `r = θ·sin(θ)`) fail every test and get
     * the full run, which is the whole point of sweeping more than one turn.
     */
    private fun polarTurns(expr: Expression): Int {
        val probes = DoubleArray(PERIOD_PROBES) { 2 * PI * ((it * GOLDEN_RATIO) % 1.0) }
        val base = DoubleArray(PERIOD_PROBES) { evalOrNaN(expr, probes[it], AngleMode.RADIANS) }
        for (turns in 1 until POLAR_TURNS) {
            if (repeatsAfterTurns(expr, probes, base, turns)) return turns
        }
        return POLAR_TURNS
    }

    /** Whether the curve reproduces every probe after [turns] whole turns. */
    private fun repeatsAfterTurns(
        expr: Expression,
        probes: DoubleArray,
        base: DoubleArray,
        turns: Int,
    ): Boolean {
        val offset = 2 * PI * turns
        for (i in probes.indices) {
            val a = base[i]
            val b = evalOrNaN(expr, probes[i] + offset, AngleMode.RADIANS)
            if (a.isNaN() && b.isNaN()) continue
            // Negated rather than `>`, so a NaN difference counts as a mismatch.
            if (!(abs(b - a) <= PERIOD_TOLERANCE * (1 + abs(a)))) return false
        }
        return true
    }

    // ---- Feature discovery ----

    /**
     * Every notable point within [radius] graph units of [at], nearest first.
     *
     * Only geometry near the tap is examined, so this stays cheap enough to run on each
     * touch no matter how many curves are on screen.
     */
    fun featuresNear(
        curves: List<SampledCurve>,
        at: GraphPoint,
        radius: Double,
        angle: AngleMode,
    ): List<GraphFeature> {
        val found = ArrayList<GraphFeature>()
        for (curve in curves) singleCurveFeatures(curve, at, radius, angle, found)
        for (i in curves.indices) {
            for (j in i + 1 until curves.size) {
                intersectionFeatures(curves[i], curves[j], at, radius, angle, found)
            }
        }
        return found
            .filter { distance(it.point, at) <= radius }
            .sortedBy { distance(it.point, at) }
            .let(::dedupe)
    }

    /** Roots, y-intercepts and local extrema of a single curve. */
    private fun singleCurveFeatures(
        curve: SampledCurve,
        at: GraphPoint,
        radius: Double,
        angle: AngleMode,
        out: MutableList<GraphFeature>,
    ) {
        for (run in curve.runs) {
            crossingsInRun(curve, run, at, radius, angle, out)
            extremaInRun(curve, run, at, radius, angle, out)
        }
    }

    /** Axis crossings within one run. */
    private fun crossingsInRun(
        curve: SampledCurve,
        run: List<GraphPoint>,
        at: GraphPoint,
        radius: Double,
        angle: AngleMode,
        out: MutableList<GraphFeature>,
    ) {
        for (i in 0 until run.size - 1) {
            val a = run[i]
            val b = run[i + 1]
            if (segmentDistance(at, a, b) > radius) continue
            // `straddles` accepts an endpoint sitting exactly on the axis, which a strict
            // sign-change test misses whenever a sample lands on it.
            axisCrossings(curve, a, b, angle, out)
        }
    }

    /** The root and y-intercept between [a] and [b], when either straddles its axis. */
    private fun axisCrossings(
        curve: SampledCurve,
        a: GraphPoint,
        b: GraphPoint,
        angle: AngleMode,
        out: MutableList<GraphFeature>,
    ) {
        if (straddles(a.y, b.y)) {
            val x = if (curve.polar) interpolateZero(a.y, b.y, a.x, b.x)
            else refineZero({ evalOrNaN(curve.expr, it, angle) }, a.x, b.x)
            out.add(GraphFeature(GraphPoint(x, 0.0), FeatureKind.ROOT, listOf(curve.id)))
        }
        if (!straddles(a.x, b.x)) return
        val y = if (curve.polar) interpolateZero(a.x, b.x, a.y, b.y)
        else evalOrNaN(curve.expr, 0.0, angle)
        if (y.isFinite()) {
            out.add(GraphFeature(GraphPoint(0.0, y), FeatureKind.Y_INTERCEPT, listOf(curve.id)))
        }
    }

    /**
     * Local extrema in y — geometrically the peaks and troughs of the drawn curve,
     * which is meaningful for polar curves too.
     */
    private fun extremaInRun(
        curve: SampledCurve,
        run: List<GraphPoint>,
        at: GraphPoint,
        radius: Double,
        angle: AngleMode,
        out: MutableList<GraphFeature>,
    ) {
        for (i in 1 until run.size - 1) {
            extremumAt(curve, run, i, at, radius, angle)?.let { out.add(it) }
        }
    }

    private fun extremumAt(
        curve: SampledCurve,
        run: List<GraphPoint>,
        i: Int,
        at: GraphPoint,
        radius: Double,
        angle: AngleMode,
    ): GraphFeature? {
        val p = run[i]
        if (distance(p, at) > radius) return null
        val isMax = p.y > run[i - 1].y && p.y > run[i + 1].y
        val isMin = p.y < run[i - 1].y && p.y < run[i + 1].y
        if (!isMax && !isMin) return null
        val kind = if (isMax) FeatureKind.MAXIMUM else FeatureKind.MINIMUM
        val point = if (curve.polar) {
            p
        } else {
            val x = refineExtremum({ evalOrNaN(curve.expr, it, angle) }, run[i - 1].x, run[i + 1].x, isMax)
            val y = evalOrNaN(curve.expr, x, angle)
            if (y.isFinite()) GraphPoint(x, y) else p
        }
        return GraphFeature(point, kind, listOf(curve.id))
    }

    /**
     * Crossings between two curves. Works for any combination of Cartesian and polar because
     * it intersects the sampled polylines geometrically; Cartesian pairs are then refined on
     * `f - g`, and are additionally checked for tangential touches, which a purely geometric
     * crossing test cannot see.
     */
    private fun intersectionFeatures(
        a: SampledCurve,
        b: SampledCurve,
        at: GraphPoint,
        radius: Double,
        angle: AngleMode,
        out: MutableList<GraphFeature>,
    ) {
        val ids = listOf(a.id, b.id)
        for (runA in a.runs) {
            scanRunAgainstCurve(runA, a, b, ids, at, radius, angle, out)
        }
        if (!a.polar && !b.polar) tangentialTouches(a, b, at, radius, angle, out)
    }

    /** Every segment of [runA] checked against every segment of curve [b]. */
    private fun scanRunAgainstCurve(
        runA: List<GraphPoint>,
        a: SampledCurve,
        b: SampledCurve,
        ids: List<Long>,
        at: GraphPoint,
        radius: Double,
        angle: AngleMode,
        out: MutableList<GraphFeature>,
    ) {
        for (i in 0 until runA.size - 1) {
            val p1 = runA[i]
            val p2 = runA[i + 1]
            if (segmentDistance(at, p1, p2) > radius) continue
            for (runB in b.runs) {
                scanSegmentAgainstRun(p1, p2, runB, a, b, ids, at, radius, angle, out)
            }
        }
    }

    /** One segment of curve A checked against every segment of one run of curve B. */
    private fun scanSegmentAgainstRun(
        p1: GraphPoint,
        p2: GraphPoint,
        runB: List<GraphPoint>,
        a: SampledCurve,
        b: SampledCurve,
        ids: List<Long>,
        at: GraphPoint,
        radius: Double,
        angle: AngleMode,
        out: MutableList<GraphFeature>,
    ) {
        for (j in 0 until runB.size - 1) {
            val q1 = runB[j]
            val q2 = runB[j + 1]
            if (segmentDistance(at, q1, q2) > radius) continue
            crossingOf(p1, p2, q1, q2, a, b, ids, angle)?.let { out.add(it) }
        }
    }

    /** The feature where segments `p1→p2` and `q1→q2` cross, or null if they don't. */
    private fun crossingOf(
        p1: GraphPoint,
        p2: GraphPoint,
        q1: GraphPoint,
        q2: GraphPoint,
        a: SampledCurve,
        b: SampledCurve,
        ids: List<Long>,
        angle: AngleMode,
    ): GraphFeature? {
        val hit = segmentIntersection(p1, p2, q1, q2) ?: return null
        if (a.polar || b.polar) return GraphFeature(hit, FeatureKind.INTERSECTION, ids)
        val lo = minOf(p1.x, p2.x, q1.x, q2.x)
        val hi = maxOf(p1.x, p2.x, q1.x, q2.x)
        val diff = { x: Double -> evalOrNaN(a.expr, x, angle) - evalOrNaN(b.expr, x, angle) }
        val x = refineZero(diff, lo, hi)
        val y = evalOrNaN(a.expr, x, angle)
        val point = if (y.isFinite()) GraphPoint(x, y) else hit
        return GraphFeature(point, FeatureKind.INTERSECTION, ids)
    }

    /**
     * Points where two Cartesian curves touch without crossing (`x²` and `2x-1` at x = 1).
     * `f - g` has a local minimum in absolute value there but never changes sign, so this
     * looks for those minima and accepts the ones that reach zero to within a fraction of a
     * pixel — [radius] stands in for the touch tolerance's scale.
     */
    private fun tangentialTouches(
        a: SampledCurve,
        b: SampledCurve,
        at: GraphPoint,
        radius: Double,
        angle: AngleMode,
        out: MutableList<GraphFeature>,
    ) {
        val diff = { x: Double -> evalOrNaN(a.expr, x, angle) - evalOrNaN(b.expr, x, angle) }
        val steps = 240
        val lo = at.x - radius
        val hi = at.x + radius
        val tolerance = radius * 1e-3
        var prev = diff(lo)
        var cur = diff(lo + (hi - lo) / steps)
        for (i in 2..steps) {
            val x = lo + (hi - lo) * i / steps
            val next = diff(x)
            val xPrev = lo + (hi - lo) * (i - 2) / steps
            touchAt(diff, a, b, xPrev, x, prev, cur, next, tolerance, angle)?.let { out.add(it) }
            prev = cur
            cur = next
        }
    }

    /** The tangential touch in the bracket ending at [x], or null when there isn't one. */
    private fun touchAt(
        diff: (Double) -> Double,
        a: SampledCurve,
        b: SampledCurve,
        xPrev: Double,
        x: Double,
        prev: Double,
        cur: Double,
        next: Double,
        tolerance: Double,
        angle: AngleMode,
    ): GraphFeature? {
        if (!prev.isFinite() || !cur.isFinite() || !next.isFinite()) return null
        if (!(abs(cur) < abs(prev) && abs(cur) < abs(next))) return null
        val xt = refineExtremum({ -abs(diff(it)) }, xPrev, x, wantMax = true)
        val dv = diff(xt)
        if (!dv.isFinite() || abs(dv) > tolerance) return null
        val y = evalOrNaN(a.expr, xt, angle)
        if (!y.isFinite()) return null
        return GraphFeature(GraphPoint(xt, y), FeatureKind.INTERSECTION, listOf(a.id, b.id))
    }

    // ---- Numeric helpers ----

    /**
     * Whether a sign change happens between [a] and [b], *including* the case where one of
     * them is exactly zero. Requiring a strict `a * b < 0` was the long-standing bug here: a
     * viewport centred on the origin makes samples land exactly on the nice crossing points,
     * so the product is `0`, not negative, and the crossing was skipped every time.
     */
    private fun straddles(a: Double, b: Double): Boolean {
        if (!a.isFinite() || !b.isFinite()) return false
        return (a <= 0.0 && b >= 0.0) || (a >= 0.0 && b <= 0.0)
    }

    /** Linear interpolation of the parameter where `va → vb` crosses zero. */
    private fun interpolateZero(va: Double, vb: Double, pa: Double, pb: Double): Double {
        val d = vb - va
        if (d == 0.0) return pa
        return pa + (pb - pa) * (-va / d)
    }

    /** Bisection for `f(x) = 0` on a bracketing interval, tolerant of exact zeros. */
    private fun refineZero(f: (Double) -> Double, lo0: Double, hi0: Double): Double {
        var lo = lo0
        var hi = hi0
        var flo = f(lo)
        val fhi = f(hi)
        if (flo == 0.0) return lo
        if (fhi == 0.0) return hi
        if (!flo.isFinite() || !fhi.isFinite() || !straddles(flo, fhi)) return (lo + hi) / 2
        repeat(REFINE_ITERS) {
            val mid = (lo + hi) / 2
            val fmid = f(mid)
            if (fmid == 0.0 || !fmid.isFinite()) return mid
            if (straddles(flo, fmid)) hi = mid else { lo = mid; flo = fmid }
        }
        return (lo + hi) / 2
    }

    /** Ternary search for the extremum of a unimodal bracket. */
    private fun refineExtremum(f: (Double) -> Double, lo0: Double, hi0: Double, wantMax: Boolean): Double {
        var lo = lo0
        var hi = hi0
        repeat(REFINE_ITERS) {
            val m1 = lo + (hi - lo) / 3
            val m2 = hi - (hi - lo) / 3
            val f1 = f(m1)
            val f2 = f(m2)
            if (!f1.isFinite() || !f2.isFinite()) return (lo + hi) / 2
            val keepLeft = if (wantMax) f1 > f2 else f1 < f2
            if (keepLeft) hi = m2 else lo = m1
        }
        return (lo + hi) / 2
    }

    // ---- Geometry ----

    private fun distance(a: GraphPoint, b: GraphPoint) = hypot(a.x - b.x, a.y - b.y)

    /** Shortest distance from [p] to the segment `a→b`. */
    private fun segmentDistance(p: GraphPoint, a: GraphPoint, b: GraphPoint): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0.0) return distance(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /** Where segments `p1→p2` and `q1→q2` cross, or null if they don't. */
    private fun segmentIntersection(p1: GraphPoint, p2: GraphPoint, q1: GraphPoint, q2: GraphPoint): GraphPoint? {
        val rx = p2.x - p1.x
        val ry = p2.y - p1.y
        val sx = q2.x - q1.x
        val sy = q2.y - q1.y
        val denom = rx * sy - ry * sx
        if (denom == 0.0) return null // parallel or collinear
        val qpx = q1.x - p1.x
        val qpy = q1.y - p1.y
        val t = (qpx * sy - qpy * sx) / denom
        val u = (qpx * ry - qpy * rx) / denom
        if (t !in 0.0..1.0 || u !in 0.0..1.0) return null
        return GraphPoint(p1.x + t * rx, p1.y + t * ry)
    }

    /** Collapses features that describe the same point, keeping the most specific kind. */
    private fun dedupe(sorted: List<GraphFeature>): List<GraphFeature> {
        val out = ArrayList<GraphFeature>()
        for (f in sorted) {
            val scale = 1e-6 * (1 + abs(f.point.x) + abs(f.point.y))
            if (out.none { distance(it.point, f.point) <= scale }) out.add(f)
        }
        return out
    }
}
