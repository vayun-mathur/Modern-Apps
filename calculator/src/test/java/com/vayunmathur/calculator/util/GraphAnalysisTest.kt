package com.vayunmathur.calculator.util

import com.vayunmathur.calculator.domain.AngleMode
import com.vayunmathur.calculator.domain.Expression
import com.vayunmathur.calculator.domain.FeatureKind
import com.vayunmathur.calculator.domain.GraphAnalysis
import com.vayunmathur.calculator.domain.GraphPoint
import com.vayunmathur.calculator.domain.SampledCurve
import com.vayunmathur.calculator.platform.GraphViewport

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphAnalysisTest {

    private fun cartesian(
        src: String,
        id: Long = 1L,
        xMin: Double = -10.0,
        xMax: Double = 10.0,
        yMin: Double = -10.0,
        yMax: Double = 10.0,
        columns: Int = 400,
    ): SampledCurve = GraphAnalysis.sample(
        id, Expression.parse(src), false, AngleMode.RADIANS, xMin, xMax, yMin, yMax, columns,
    )

    private fun assertClose(expected: Double, actual: Double, tolerance: Double = 1e-6) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    // ---- function evaluation through sampling ----

    @Test
    fun cartesianLineSamplesFollowTheFunction() {
        val curve = cartesian("x")
        assertTrue(curve.runs.isNotEmpty())
        for (run in curve.runs) {
            for (p in run) assertClose(p.x, p.y, 1e-9)
        }
    }

    @Test
    fun asymptoteIsNeverBridged() {
        val curve = cartesian("1/x", xMin = -5.0, xMax = 5.0)
        assertTrue(curve.runs.size >= 2, "1/x must split at its pole")
        val jumpLimit = 20.0 * 4
        for (run in curve.runs) {
            for (i in 0 until run.size - 1) {
                assertTrue(abs(run[i + 1].y - run[i].y) <= jumpLimit)
            }
        }
    }

    @Test
    fun undefinedRegionsSplitRuns() {
        val curve = cartesian("sqrt(x)", xMin = -5.0, xMax = 5.0)
        assertTrue(curve.runs.isNotEmpty())
        for (run in curve.runs) {
            for (p in run) assertTrue(p.x >= 0.0, "sqrt is undefined left of zero")
        }
    }

    @Test
    fun polarCircleSamplesUnitRadius() {
        val expr = Expression.parse("1")
        val curve = GraphAnalysis.sample(
            7L, expr, true, AngleMode.RADIANS, -2.0, 2.0, -2.0, 2.0, 600,
        )
        assertTrue(curve.runs.isNotEmpty())
        assertTrue(curve.runs.sumOf { it.size } > 10)
        for (run in curve.runs) {
            for (p in run) assertClose(1.0, hypot(p.x, p.y), 1e-6)
        }
    }

    // ---- feature discovery (tap analysis) ----

    @Test
    fun rootIsFoundNearTap() {
        val curve = cartesian("x-2")
        val found = GraphAnalysis.featuresNear(listOf(curve), GraphPoint(2.0, 0.0), 0.6, AngleMode.RADIANS)
        val root = found.firstOrNull { it.kind == FeatureKind.ROOT }
        assertNotNull(root, "expected a root near x = 2, got $found")
        assertClose(2.0, root.point.x)
        assertClose(0.0, root.point.y)
    }

    @Test
    fun yInterceptIsFoundNearTap() {
        val curve = cartesian("2*x+1")
        val found = GraphAnalysis.featuresNear(listOf(curve), GraphPoint(0.0, 1.0), 0.6, AngleMode.RADIANS)
        val hit = found.firstOrNull { it.kind == FeatureKind.Y_INTERCEPT }
        assertNotNull(hit, "expected a y-intercept near (0, 1), got $found")
        assertClose(0.0, hit.point.x)
        assertClose(1.0, hit.point.y)
    }

    @Test
    fun minimumIsFoundNearTap() {
        val curve = cartesian("x^2+x+1")
        val at = GraphPoint(-0.5, 0.75)
        val found = GraphAnalysis.featuresNear(listOf(curve), at, 0.6, AngleMode.RADIANS)
        val hit = found.firstOrNull { it.kind == FeatureKind.MINIMUM }
        assertNotNull(hit, "expected a minimum near (-0.5, 0.75), got $found")
        assertClose(-0.5, hit.point.x)
        assertClose(0.75, hit.point.y)
    }

    @Test
    fun intersectionListsBothCurveIds() {
        val a = cartesian("x", id = 1L)
        val b = cartesian("-x+2", id = 2L)
        val found = GraphAnalysis.featuresNear(listOf(a, b), GraphPoint(1.0, 1.0), 1.0, AngleMode.RADIANS)
        val hit = found.firstOrNull { it.kind == FeatureKind.INTERSECTION }
        assertNotNull(hit, "expected an intersection near (1, 1), got $found")
        assertEquals(listOf(1L, 2L), hit.curveIds)
        assertClose(1.0, hit.point.x)
        assertClose(1.0, hit.point.y)
    }

    @Test
    fun farTapFindsNothing() {
        val curve = cartesian("x")
        val found = GraphAnalysis.featuresNear(listOf(curve), GraphPoint(100.0, 100.0), 0.5, AngleMode.RADIANS)
        assertTrue(found.isEmpty(), "nothing is near (100, 100)")
    }

    @Test
    fun resultsStayWithinRadiusAndNearestFirst() {
        val curve = cartesian("x-2")
        val at = GraphPoint(2.4, 0.0)
        val found = GraphAnalysis.featuresNear(listOf(curve), at, 1.0, AngleMode.RADIANS)
        assertTrue(found.isNotEmpty())
        var previous = -1.0
        for (f in found) {
            val d = hypot(f.point.x - at.x, f.point.y - at.y)
            assertTrue(d <= 1.0, "feature $f is outside the tap radius")
            assertTrue(d >= previous, "features are not nearest-first")
            previous = d
        }
    }

    // ---- viewport transform and tap mapping ----

    @Test
    fun viewportCenterMapsToScreenCenter() {
        val viewport = GraphViewport()
        assertClose(0.0, viewport.graphX(300f, 600f))
        assertClose(0.0, viewport.graphY(200f, 400f))
    }

    @Test
    fun screenGraphRoundTrip() {
        val viewport = GraphViewport(centerX = 1.5, centerY = -2.0, scale = 60.0)
        assertClose(137.0, viewport.screenX(viewport.graphX(137f, 600f), 600f).toDouble(), 1e-3)
        assertClose(251.0, viewport.screenY(viewport.graphY(251f, 400f), 400f).toDouble(), 1e-3)
    }

    @Test
    fun boundsSpanWidthOverScale() {
        val bounds = GraphViewport().bounds(600f, 400f)
        assertClose(10.0, bounds.xMax - bounds.xMin, 1e-9)
        assertTrue(bounds.xMin < 0.0 && 0.0 < bounds.xMax)
        assertTrue(bounds.yMin < 0.0 && 0.0 < bounds.yMax)
    }

    @Test
    fun zoomKeepsCentroidFixed() {
        val viewport = GraphViewport()
        val zoomed = viewport.transformed(500f, 200f, 0f, 0f, 2f, 800f, 600f)
        assertEquals(120.0, zoomed.scale)
        assertClose(viewport.graphX(500f, 800f), zoomed.graphX(500f, 800f), 1e-9)
        assertClose(viewport.graphY(200f, 600f), zoomed.graphY(200f, 600f), 1e-9)
    }

    @Test
    fun zoomClampsToScaleLimits() {
        val viewport = GraphViewport()
        assertEquals(400000.0, viewport.transformed(0f, 0f, 0f, 0f, 1e9f, 8f, 6f).scale)
        assertEquals(2.0, viewport.transformed(0f, 0f, 0f, 0f, 1e-9f, 8f, 6f).scale)
    }

    @Test
    fun panMovesCenterAgainstTheDrag() {
        val moved = GraphViewport().transformed(400f, 300f, 60f, 30f, 1f, 800f, 600f)
        assertClose(-1.0, moved.centerX, 1e-9)
        assertClose(0.5, moved.centerY, 1e-9)
    }
}
