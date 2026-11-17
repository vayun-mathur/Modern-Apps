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
 * Text-painting tests for [drawSafePage], against REAL pixels.
 *
 * These need a working `android.graphics`: the stub android.jar answers `Typeface.create` with
 * null, so every Text primitive throws before it reaches the paint guard. Robolectric supplies
 * a real Skia-backed `Canvas`, `Paint` and `Typeface`, which also means the assertions here can
 * be about ink on a bitmap rather than about which calls were issued.
 *
 * The structural save-stack tests do not need this and live in [DrawSafePageTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawSafePageTextTest {

    private val pageWidth = 200f
    private val pageHeight = 100f

    /** Paint [prims] onto a white bitmap and return it. */
    private fun rasterize(vararg prims: PdfPrimitive): android.graphics.Bitmap {
        val bitmap = android.graphics.Bitmap.createBitmap(
            pageWidth.toInt(), pageHeight.toInt(), android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val page = SafePdfPage(pageWidth, pageHeight, prims.toList())
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, Canvas(canvas), Size(pageWidth, pageHeight),
        ) {
            drawSafePage(page)
        }
        return bitmap
    }

    /** How many pixels are not the white the bitmap was cleared to. */
    private fun inkedPixels(bitmap: android.graphics.Bitmap): Int {
        var inked = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != android.graphics.Color.WHITE) inked++
            }
        }
        return inked
    }

    private fun text(
        renderMode: Int,
        strokeColor: Int? = null,
        strokeWidth: Float = 0f,
        content: String = "Hamburgefonstiv",
        outline: Boolean = false,
        y: Float = 40f,
        color: Int = 0xFF000000.toInt(),
    ) = PdfPrimitive.Text(
        origin = Offset(4f, y),
        size = 24f,
        color = color,
        text = content,
        strokeColor = strokeColor,
        strokeWidth = strokeWidth,
        renderMode = renderMode,
        outline = outline,
    )

    /**
     * The control for every "paints nothing" assertion below. In legacy graphics mode Robolectric
     * records draw calls without rasterizing, so every pixel reads back as 0 and an ink count of
     * zero becomes unreachable — which would make those assertions pass vacuously. This pins the
     * cleared bitmap to actually being white.
     */
    @Test
    fun theHarnessRasterizesSoAnInkCountIsMeaningful() {
        val blank = rasterize()
        assertEquals(
            0,
            inkedPixels(blank),
            "a cleared bitmap is not white, so this harness is not rasterizing and every " +
                "no-ink assertion in this class would be vacuous",
        )
        assertTrue(inkedPixels(rasterize(text(renderMode = 0))) > 0, "no ink for render mode 0")
    }

    /**
     * PDF 32000-1 §9.3.6 mode 3 is invisible, and that is how every scanned document carries its
     * OCR text layer. If it ever paints, every scanned PDF overprints its OCR text on top of the
     * scan. There are two guards; the Rust one (argb 0) is covered on that side, so this asserts
     * the Kotlin paint guard.
     */
    @Test
    fun renderModeThreePaintsNoInk() {
        assertEquals(
            0,
            inkedPixels(rasterize(text(renderMode = 3))),
            "render mode 3 put ink on the page: a scanned PDF would overprint its invisible " +
                "OCR layer on top of the scan",
        )
    }

    /** Mode 3 must stay invisible even when Rust hands it a stroke colour and width. */
    @Test
    fun renderModeThreePaintsNoInkEvenWithAStroke() {
        assertEquals(
            0,
            inkedPixels(rasterize(text(renderMode = 3, strokeColor = 0xFFFF0000.toInt(), strokeWidth = 3f))),
            "mode 3 painted a stroke",
        )
    }

    /** Mode 7 is clip-only and likewise paints nothing. */
    @Test
    fun renderModeSevenPaintsNoInk() {
        assertEquals(
            0,
            inkedPixels(rasterize(text(renderMode = 7, strokeColor = 0xFFFF0000.toInt(), strokeWidth = 3f))),
            "mode 7 is clip-only but painted",
        )
    }

    /** A fill covering the whole page in page space (y-up), used as the clip's victim. */
    private fun pageFill() = PdfPrimitive.FillPath(
        color = 0xFF000000.toInt(),
        evenOdd = false,
        contours = listOf(
            listOf(
                Offset(0f, 0f), Offset(pageWidth, 0f),
                Offset(pageWidth, pageHeight), Offset(0f, pageHeight),
            )
        ),
    )

    /**
     * Mode 7 paints nothing but MUST still contribute its glyph outlines to the clip
     * (§9.3.6 Table 106 "clip only"; §9.4.3 intersects them at ET). Painting and clipping
     * are separate halves of the mode and it is easy to suppress both together.
     *
     * This became load-bearing across the language boundary: `interpret.rs:118`
     * `hidden_render_mode` routes an OC-hidden CLIPPING run to 7 rather than collapsing it
     * to 3, precisely so the clip contribution survives. If this accumulator stopped taking
     * mode 7, `TextClipApply` would open a level and narrow nothing, and the art meant to
     * show only inside the letterforms would paint as a full opaque rectangle.
     */
    @Test
    fun renderModeSevenStillNarrowsTheTextClip() {
        val clipped = inkedPixels(
            rasterize(
                text(renderMode = 7),
                PdfPrimitive.TextClipApply,
                pageFill(),
                PdfPrimitive.ClipPop,
            )
        )
        val unclipped = inkedPixels(rasterize(pageFill()))
        assertEquals(
            (pageWidth * pageHeight).toInt(),
            unclipped,
            "the control must cover the page, or the comparison below proves nothing",
        )
        assertTrue(clipped > 0, "mode 7 accumulated no outlines, so the clip kept nothing")
        assertTrue(
            clipped < unclipped / 2,
            "the fill was not clipped to the glyphs: $clipped of $unclipped pixels survived",
        )
    }

    /**
     * The empty case, in pixels. §9.4.3 combines the accumulated outlines with the current clip
     * by INTERSECTION, so a `TextClipApply` that accumulated NO outlines clips to empty and the
     * fill after it must paint nothing at all. Treating "no outlines" as "no clip" is the one
     * direction that adds ink: the fill would cover the entire page instead, which is exactly
     * how the "image visible through text" idiom turns into an opaque rectangle over the page.
     */
    @Test
    fun aTextClipWithNoAccumulatedGlyphsClipsEverythingAway() {
        val inked = inkedPixels(
            rasterize(
                PdfPrimitive.TextClipApply,
                pageFill(),
                PdfPrimitive.ClipPop,
            )
        )
        assertEquals(
            0,
            inked,
            "an empty text clip let the fill through: $inked pixels painted where the " +
                "intersection with an empty accumulation must keep nothing",
        )
    }

    /** The clip is released by its `ClipPop`, so content after it paints normally again. */
    @Test
    fun anEmptyTextClipIsReleasedByItsClipPop() {
        val inked = inkedPixels(
            rasterize(
                PdfPrimitive.TextClipApply,
                PdfPrimitive.ClipPop,
                pageFill(),
            )
        )
        assertEquals(
            (pageWidth * pageHeight).toInt(),
            inked,
            "the empty text clip outlived its ClipPop and suppressed later content",
        )
    }

    /**
     * A CROSS-LANGUAGE CONTRACT. Rust emits the clip-mode Text record for a Type 3 font with
     * `argb: 0` (draw.rs), because the CharProc already supplies the real ink and a painted
     * substitute glyph would double-draw over it. The record exists ONLY to carry an outline to
     * this accumulator, so accumulation must not be gated on colour or alpha. If it ever were,
     * every Type 3 run in modes 4-6 would accumulate nothing, and — now that an empty text clip
     * correctly clips to NOTHING — that content would vanish rather than merely lose its clip.
     */
    @Test
    fun aFullyTransparentClipRecordStillNarrowsTheTextClip() {
        val clipped = inkedPixels(
            rasterize(
                text(renderMode = 4, color = 0),
                PdfPrimitive.TextClipApply,
                pageFill(),
                PdfPrimitive.ClipPop,
            )
        )
        assertTrue(
            clipped > 0,
            "a transparent (argb 0) clip record contributed no outline, so the clip kept " +
                "nothing — Type 3 content in modes 4-6 would disappear",
        )
        assertTrue(
            clipped < (pageWidth * pageHeight).toInt() / 2,
            "the fill was not clipped to the glyphs: $clipped pixels survived",
        )
    }

    /**
     * The other half of that contract: `outline = true` means Rust already painted the glyph
     * from its embedded outline as Fill prims and this record is selection/search only, so it
     * is skipped before the accumulator. Rust only sets it for modes 0-2, never for a clip
     * mode; pin the Kotlin side so the two cannot drift into silently dropping a clip.
     */
    @Test
    fun anOutlineRecordContributesNothingToTheTextClip() {
        val inked = inkedPixels(
            rasterize(
                text(renderMode = 4, outline = true),
                PdfPrimitive.TextClipApply,
                pageFill(),
                PdfPrimitive.ClipPop,
            )
        )
        assertEquals(0, inked, "an outline=true record must not reach the clip accumulator")
    }

    /**
     * Whitespace-only clip runs, settled by measurement rather than by reading.
     *
     * A space has no contours in any standard face, so `getTextPath(" ")` yields an EMPTY path.
     * That means the `isBlank()` skip in the Text arm is inert FOR CLIPPING — accumulating a
     * whitespace record would add nothing anyway, so the clip comes out empty either way, and
     * an empty clip is what §9.4.3 requires for a run that shows no glyph outlines. The skip is
     * load-bearing for the PAINT path, not this one. Recorded so nobody "fixes" the blank case
     * by falling through to NO clip, which would let the fill paint over the whole page again.
     */
    @Test
    fun aWhitespaceOnlyClipRunHasNoOutlinesAndSoClipsEverythingAway() {
        val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 24f }
        val spacePath = android.graphics.Path()
        paint.getTextPath("   ", 0, 3, 4f, 40f, spacePath)
        assertTrue(
            spacePath.isEmpty,
            "a space produced glyph contours, so the reasoning below does not hold",
        )

        val inked = inkedPixels(
            rasterize(
                text(renderMode = 7, content = "   "),
                PdfPrimitive.TextClipApply,
                pageFill(),
                PdfPrimitive.ClipPop,
            )
        )
        assertEquals(0, inked, "a whitespace-only text clip must keep nothing")
    }

    /**
     * The precondition for emitting real-outline ink in modes 4-6 alongside a clip record:
     * a fully transparent record must PAINT nothing while still being accumulable. Kotlin has
     * no "non-painting" flag for modes 4-6 — `outline = true` would suppress the paint but is
     * read before the accumulator and would drop the clip too — so transparency is the only
     * lever that separates the two roles. Pinned because a change to the paint path (a colour
     * floor, an alpha coercion, a blend default) would silently start double-painting a
     * substitute face over the real glyph contours.
     */
    @Test
    fun aFullyTransparentClipRecordPaintsNoInkOfItsOwn() {
        for (rm in 4..6) {
            assertEquals(
                0,
                inkedPixels(rasterize(text(renderMode = rm, color = 0))),
                "a transparent mode-$rm clip record painted ink, so it would double-draw a " +
                    "substitute face over glyphs already inked from their real outline",
            )
        }
    }

    /**
     * The counterpart: the guard must be exactly modes 3 and 7 and nothing wider. A guard that
     * suppressed too much would blank out real text, so pin every painting mode.
     */
    @Test
    fun everyOtherRenderModePaintsInk() {
        for (rm in listOf(0, 1, 2, 4, 5, 6)) {
            val inked = inkedPixels(
                rasterize(text(renderMode = rm, strokeColor = 0xFFFF0000.toInt(), strokeWidth = 1f))
            )
            assertTrue(inked > 0, "render mode $rm painted nothing")
        }
    }

    /**
     * An outline glyph was already painted through its real outline as Fill prims; the Text prim
     * is kept only for selection and search, so painting it double-strikes the glyph.
     */
    @Test
    fun outlineTextIsNeverPainted() {
        assertEquals(0, inkedPixels(rasterize(text(renderMode = 0, outline = true))))
    }

    @Test
    fun blankTextIsNeverPainted() {
        assertEquals(0, inkedPixels(rasterize(text(renderMode = 0, content = "   "))))
    }

    /**
     * PDF page space has its origin bottom-left and the canvas top-left, and the flip happens
     * only here in Kotlin. Text at a low page y must land near the BOTTOM of the canvas.
     */
    @Test
    fun textIsYFlippedIntoCanvasSpace() {
        val low = rasterize(text(renderMode = 0, y = 10f))
        val high = rasterize(text(renderMode = 0, y = 90f))
        assertTrue(topmostInkedRow(low) > topmostInkedRow(high),
            "a lower page y must paint further down the canvas, not further up")
        assertTrue(topmostInkedRow(low) > bitmapMidpoint(), "page y=10 should be in the lower half")
        assertTrue(topmostInkedRow(high) < bitmapMidpoint(), "page y=90 should be in the upper half")
    }

    private fun bitmapMidpoint() = (pageHeight / 2f).toInt()

    /**
     * The Y-flip must be applied to path geometry exactly as it is to text baselines. A fill
     * occupying the bottom 20pt of page space must ink the BOTTOM of the canvas; an unflipped
     * or doubly-flipped path lands mirrored, which puts every graphic on the wrong half.
     */
    @Test
    fun fillsAreYFlippedIntoCanvasSpace() {
        val bottomBand = listOf(
            Offset(0f, 0f), Offset(pageWidth, 0f), Offset(pageWidth, 20f), Offset(0f, 20f),
        )
        val bitmap = rasterize(
            PdfPrimitive.FillPath(0xFF000000.toInt(), false, listOf(bottomBand)),
        )
        assertTrue(inkedPixels(bitmap) > 0, "the fill painted nothing")
        assertTrue(
            topmostInkedRow(bitmap) > bitmapMidpoint(),
            "a fill at page y 0..20 inked canvas row ${topmostInkedRow(bitmap)}, i.e. the top " +
                "half: path geometry is not Y-flipped the way text baselines are",
        )
    }

    /** And to strokes, which take a separate path-building route from fills. */
    @Test
    fun strokesAreYFlippedIntoCanvasSpace() {
        val bitmap = rasterize(
            PdfPrimitive.StrokePath(
                color = 0xFF000000.toInt(), width = 4f, dash = FloatArray(0), dashPhase = 0f,
                points = listOf(Offset(0f, 10f), Offset(pageWidth, 10f)),
            ),
        )
        assertTrue(inkedPixels(bitmap) > 0, "the stroke painted nothing")
        assertTrue(
            topmostInkedRow(bitmap) > bitmapMidpoint(),
            "a stroke along page y=10 inked canvas row ${topmostInkedRow(bitmap)}: stroke " +
                "geometry is not Y-flipped consistently with fills",
        )
    }

    /**
     * And to clip paths. A clip is flipped independently of the content it bounds, so if the two
     * disagree the clip masks off the wrong half — content vanishes rather than merely shifting,
     * which is why this is asserted separately from the fill case above.
     */
    @Test
    fun clipPathsAreYFlippedWithTheContentTheyClip() {
        val bottomBand = listOf(
            Offset(0f, 0f), Offset(pageWidth, 0f), Offset(pageWidth, 20f), Offset(0f, 20f),
        )
        val wholePage = listOf(
            Offset(0f, 0f), Offset(pageWidth, 0f), Offset(pageWidth, pageHeight), Offset(0f, pageHeight),
        )
        val bitmap = rasterize(
            PdfPrimitive.ClipPush(false, bottomBand),
            PdfPrimitive.FillPath(0xFF000000.toInt(), false, listOf(wholePage)),
            PdfPrimitive.ClipPop,
        )
        assertTrue(inkedPixels(bitmap) > 0, "the clip removed all the ink, so it was mis-flipped")
        assertTrue(
            topmostInkedRow(bitmap) > bitmapMidpoint(),
            "a full-page fill clipped to page y 0..20 inked canvas row " +
                "${topmostInkedRow(bitmap)}: the clip path and the fill disagree on the Y-flip",
        )
    }

    private fun topmostInkedRow(bitmap: android.graphics.Bitmap): Int {
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getPixel(x, y) != android.graphics.Color.WHITE) return y
            }
        }
        return bitmap.height
    }

    /**
     * Round 1 found dash intervals were not scaled to the canvas while the stroke width was, so
     * a dashed rule looked solid once zoomed. At a 4x scale a dashed line must still leave gaps.
     */
    @Test
    fun dashIntervalsScaleWithTheCanvasSoADashedRuleStaysDashed() {
        val dashed = PdfPrimitive.StrokePath(
            color = 0xFF000000.toInt(), width = 0.25f,
            dash = floatArrayOf(2f, 2f), dashPhase = 0f,
            points = listOf(Offset(0f, 50f), Offset(pageWidth, 50f)),
        )
        val bitmap = android.graphics.Bitmap.createBitmap(
            (pageWidth * 4).toInt(), (pageHeight * 4).toInt(), android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val page = SafePdfPage(pageWidth, pageHeight, listOf(dashed))
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, Canvas(canvas),
            Size(pageWidth * 4, pageHeight * 4),
        ) {
            drawSafePage(page)
        }
        val row = (pageHeight * 4 / 2).toInt()
        var inked = 0
        for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, row) != android.graphics.Color.WHITE) inked++
        }
        assertTrue(inked > 0, "the dashed rule painted nothing at all")
        assertTrue(
            inked < bitmap.width * 3 / 4,
            "the dashed rule covered $inked of ${bitmap.width} pixels: dash intervals were not " +
                "scaled to the canvas, so it renders effectively solid when zoomed",
        )
    }

    /**
     * `setPolyToPoly` leaves the matrix untouched for a degenerate destination quad, so a
     * zero-area image must be dropped rather than painted unscaled at the origin over real
     * content.
     */
    @Test
    fun aDegenerateImageTransformPaintsNothing() {
        val cell = android.graphics.Bitmap.createBitmap(
            4, 4, android.graphics.Bitmap.Config.ARGB_8888,
        )
        cell.eraseColor(android.graphics.Color.RED)
        val inked = inkedPixels(
            rasterize(
                PdfPrimitive.Image(floatArrayOf(0f, 0f, 0f, 0f, 10f, 10f), bitmap = cell),
            )
        )
        assertEquals(0, inked, "a zero-area image was painted, covering real content")
    }

    /** And a well-formed transform does place the bitmap where the CTM says. */
    @Test
    fun aWellFormedImageTransformPaintsTheBitmap() {
        val cell = android.graphics.Bitmap.createBitmap(
            4, 4, android.graphics.Bitmap.Config.ARGB_8888,
        )
        cell.eraseColor(android.graphics.Color.RED)
        val bitmap = rasterize(
            PdfPrimitive.Image(floatArrayOf(40f, 0f, 0f, 40f, 10f, 10f), bitmap = cell),
        )
        assertTrue(inkedPixels(bitmap) > 0, "a well-formed image painted nothing")
        // The CTM's unit square spans page x 10..50, y 10..50, which the Y-flip puts at canvas
        // y 50..90 — the lower half of a 100px-tall canvas.
        assertEquals(
            android.graphics.Color.RED,
            bitmap.getPixel(30, 70),
            "the image did not land where its CTM and the Y-flip put it",
        )
    }

    // ---- Tr paint ORDER and the zero-width stroke (PDF 32000-1 9.3.6 / 8.4.3.2) ----

    /**
     * Records the style of each `drawText` in the order it was issued.
     *
     * `drawSafePage` keeps two paints — a FILL one for the glyph body and a STROKE one for the
     * outline — so the style sequence IS the paint order, which no pixel assertion can recover
     * once the two have been composited.
     */
    private class TextOrderSpy(bitmap: android.graphics.Bitmap) : android.graphics.Canvas(bitmap) {
        val styles = mutableListOf<android.graphics.Paint.Style>()

        override fun drawText(text: String, x: Float, y: Float, paint: android.graphics.Paint) {
            styles.add(paint.style)
            super.drawText(text, x, y, paint)
        }
    }

    /** The `drawText` styles [prims] issue, in order. */
    private fun textOrder(vararg prims: PdfPrimitive): List<android.graphics.Paint.Style> {
        val bitmap = android.graphics.Bitmap.createBitmap(
            pageWidth.toInt(), pageHeight.toInt(), android.graphics.Bitmap.Config.ARGB_8888,
        )
        val spy = TextOrderSpy(bitmap)
        spy.drawColor(android.graphics.Color.WHITE)
        val page = SafePdfPage(pageWidth, pageHeight, prims.toList())
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, Canvas(spy), Size(pageWidth, pageHeight),
        ) {
            drawSafePage(page)
        }
        return spy.styles
    }

    /**
     * Table 106 names modes 2 and 6 "Fill, then stroke text", and the order is visible whenever
     * the two colours differ: stroking first lets the fill paint over the inner half of the
     * stroke, so a black outline round white display text comes out at half its weight.
     */
    @Test
    fun modesTwoAndSixFillBeforeTheyStroke() {
        for (rm in listOf(2, 6)) {
            assertEquals(
                listOf(android.graphics.Paint.Style.FILL, android.graphics.Paint.Style.STROKE),
                textOrder(text(renderMode = rm, strokeColor = 0xFF0000FF.toInt(), strokeWidth = 3f)),
                "render mode $rm painted the stroke before the fill, so the fill covers the " +
                    "inner half of the outline",
            )
        }
    }

    /** Modes 1 and 5 are stroke-only: the fill must not be painted at all. */
    @Test
    fun modesOneAndFiveStrokeWithoutFilling() {
        for (rm in listOf(1, 5)) {
            assertEquals(
                listOf(android.graphics.Paint.Style.STROKE),
                textOrder(text(renderMode = rm, strokeColor = 0xFF0000FF.toInt(), strokeWidth = 3f)),
                "render mode $rm is stroke-only",
            )
        }
    }

    /**
     * §8.4.3.2 makes a line width of ZERO the thinnest line the device can render, not the
     * absence of a line — and `draw.rs` forwards `device_stroke_w` for text without the
     * `.max(0.1)` it applies to `Prim::Stroke`, so `0 w 1 Tr` really does arrive here as width
     * zero. Treating that as "no stroke" fell through to the fill branch, which for a
     * stroke-only mode paints `prim.color` — Rust sets that to the STROKE colour there — so
     * hairline-outlined text rendered as solid glyphs.
     */
    @Test
    fun aZeroWidthStrokeStillStrokesRatherThanFilling() {
        assertEquals(
            listOf(android.graphics.Paint.Style.STROKE),
            textOrder(text(renderMode = 1, strokeColor = 0xFF0000FF.toInt(), strokeWidth = 0f)),
            "a zero-width stroke was treated as no stroke, so mode 1 painted a solid fill",
        )
    }

    /**
     * The counterweight: with no stroke COLOUR there is nothing to stroke with, and Rust's
     * no-metrics path emits mode 1 exactly like that. It must still show something.
     */
    @Test
    fun modeOneWithNoStrokeColourStillPaintsTheFill() {
        assertEquals(
            listOf(android.graphics.Paint.Style.FILL),
            textOrder(text(renderMode = 1, strokeColor = null)),
            "mode 1 with no stroke colour painted nothing at all",
        )
    }

    /** A negative width is not a width: it must not reach Skia as one. */
    @Test
    fun aNegativeStrokeWidthIsNotTreatedAsAStroke() {
        assertEquals(
            listOf(android.graphics.Paint.Style.FILL),
            textOrder(text(renderMode = 0, strokeColor = 0xFF0000FF.toInt(), strokeWidth = -2f)),
        )
    }
}
