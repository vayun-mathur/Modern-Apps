package com.vayunmathur.pdf.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.vayunmathur.pdf.platform.PdfPrimitive
import com.vayunmathur.pdf.platform.SafePdfPage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which stroke parameters take the page->canvas scale, pinned against real Skia output.
 *
 * The ground truth is what `draw.rs::emit_stroke` EMITS, not the wire doc: it multiplies the
 * line width, every dash interval and the dash phase by the CTM scale, leaving all three in the
 * same page space as the point coordinates, and passes `gs.miter_limit` through untouched. The
 * miter limit is a ratio of miter length to line width (PDF 32000-1 8.4.3.5) and is therefore
 * unitless — scaling it would silently change which corners get bevelled as the user zooms.
 *
 * The failure these guard is invisible at 1:1 and only appears under magnification, which is
 * exactly where nobody looks: an unscaled dash interval leaves the period at its page-space
 * pixel count, so a zoomed-in dashed rule reads as a solid one.
 *
 * These need real graphics — the stub android.jar answers every `Paint` getter with a default,
 * so a paint assertion there would pass whatever the code did. The structural save-stack tests
 * live in [DrawSafePageTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawSafePageStrokeTest {

    private val pageWidth = 100f
    private val pageHeight = 20f

    /** Page->canvas factor every test here renders at, chosen large enough to be unmistakable. */
    private val scale = 4f

    private val canvasWidth = pageWidth * scale
    private val canvasHeight = pageHeight * scale

    /** Captures the framework [android.graphics.Paint] each stroked path is drawn with. */
    private class PaintSpy(bitmap: android.graphics.Bitmap) : android.graphics.Canvas(bitmap) {
        val strokePaints = mutableListOf<android.graphics.Paint>()

        override fun drawPath(path: android.graphics.Path, paint: android.graphics.Paint) {
            if (paint.style != android.graphics.Paint.Style.FILL) {
                strokePaints.add(android.graphics.Paint(paint))
            }
            super.drawPath(path, paint)
        }
    }

    private class Rendered(val bitmap: android.graphics.Bitmap, val strokePaints: List<android.graphics.Paint>)

    private fun render(vararg prims: PdfPrimitive): Rendered {
        val bitmap = android.graphics.Bitmap.createBitmap(
            canvasWidth.toInt(), canvasHeight.toInt(), android.graphics.Bitmap.Config.ARGB_8888,
        )
        val spy = PaintSpy(bitmap)
        spy.drawColor(android.graphics.Color.WHITE)
        val page = SafePdfPage(pageWidth, pageHeight, prims.toList())
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, Canvas(spy), Size(canvasWidth, canvasHeight),
        ) {
            drawSafePage(page)
        }
        return Rendered(bitmap, spy.strokePaints)
    }

    /** A horizontal rule across the full page width, at page y = 10 (canvas y = 40). */
    private fun rule(
        width: Float = 2f,
        dash: FloatArray = FloatArray(0),
        dashPhase: Float = 0f,
        miter: Float = 7f,
    ) = PdfPrimitive.StrokePath(
        color = 0xFF000000.toInt(),
        width = width,
        dash = dash,
        dashPhase = dashPhase,
        points = listOf(Offset(0f, 10f), Offset(pageWidth, 10f)),
        cap = 0,
        join = 0,
        miter = miter,
    )

    /** Whether canvas pixel (x, [ruleRow]) carries any ink. */
    private fun inkedAt(bitmap: android.graphics.Bitmap, x: Int) =
        bitmap.getPixel(x, ruleRow) != android.graphics.Color.WHITE

    /** Canvas row through the middle of a [rule]: the Y-flip puts page y=10 at 80 - 40. */
    private val ruleRow = (canvasHeight - 10f * scale).toInt()

    /** Number of separate inked runs along [ruleRow] — one per painted dash. */
    private fun dashRuns(bitmap: android.graphics.Bitmap): Int {
        var runs = 0
        var wasInked = false
        for (x in 0 until bitmap.width) {
            val inked = inkedAt(bitmap, x)
            if (inked && !wasInked) runs++
            wasInked = inked
        }
        return runs
    }

    /**
     * The control: without it every "the dash pattern looks like X" assertion below could pass
     * on a harness that never rasterized.
     */
    @Test
    fun theHarnessRasterizesSoARunCountIsMeaningful() {
        val solid = render(rule())
        assertEquals(1, dashRuns(solid.bitmap), "a solid rule is one unbroken run")
        assertTrue(inkedAt(solid.bitmap, 0), "a solid rule starts at the left edge")
        assertTrue(inkedAt(solid.bitmap, canvasWidth.toInt() - 1), "and reaches the right edge")
    }

    /** Line width is page-space (draw.rs multiplies `gs.line_width` by the CTM scale). */
    @Test
    fun theLineWidthTakesThePageToCanvasScale() {
        val paint = render(rule(width = 2f)).strokePaints.single()
        assertEquals(
            2f * scale, paint.strokeWidth, 1e-3f,
            "a 2pt rule at ${scale}x must be ${2f * scale} canvas px",
        )
    }

    /**
     * The miter limit is the ratio miterLength/lineWidth (PDF 32000-1 8.4.3.5), so it is
     * unitless and scaling it would change which joins bevel as the page is zoomed.
     */
    @Test
    fun theMiterLimitIsUnitlessAndNotScaled() {
        val paint = render(rule(miter = 7f)).strokePaints.single()
        assertEquals(
            7f, paint.strokeMiter, 1e-3f,
            "the miter limit was scaled: it is a ratio, not a length",
        )
    }

    /**
     * Dash intervals are page-space, so the on/off PERIOD must grow with the zoom. A 5+5 page
     * pattern across a 100pt page is ten dashes at any scale; leaving the intervals in page
     * units would make it forty at 4x, and at real zoom levels the gaps close up entirely and
     * the rule reads as solid.
     */
    @Test
    fun dashIntervalsTakeThePageToCanvasScale() {
        val dashed = render(rule(dash = floatArrayOf(5f, 5f)))
        assertEquals(
            10, dashRuns(dashed.bitmap),
            "the dash period did not scale with the page: a 5+5 pattern over 100pt is ten " +
                "dashes at every zoom level",
        )
    }

    /**
     * The dash phase is a distance into the pattern (8.4.3.6), in the same units as the
     * intervals, so it scales with them. Half a period of phase must start the rule on a GAP;
     * an unscaled phase leaves it starting on ink.
     */
    @Test
    fun theDashPhaseTakesThePageToCanvasScale() {
        val phased = render(rule(dash = floatArrayOf(5f, 5f), dashPhase = 5f))
        assertTrue(
            !inkedAt(phased.bitmap, 0),
            "half a period of dash phase must open with a gap; the phase was not scaled",
        )
        assertTrue(
            inkedAt(phased.bitmap, (5f * scale + 5f).toInt()),
            "the first dash must begin one half-period in",
        )
    }

    /** A single-entry dash array is below what Skia accepts; it must not abort the page. */
    @Test
    fun aDashArrayTooShortToUseFallsBackToASolidRule() {
        val page = render(rule(dash = floatArrayOf(5f)), rule(width = 1f))
        assertEquals(2, page.strokePaints.size, "a one-entry dash array took the page down")
    }
}
