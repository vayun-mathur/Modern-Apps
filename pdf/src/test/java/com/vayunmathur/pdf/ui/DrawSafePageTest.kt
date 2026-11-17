package com.vayunmathur.pdf.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.vayunmathur.pdf.platform.BlendMode
import com.vayunmathur.pdf.platform.PdfPrimitive
import com.vayunmathur.pdf.platform.SafePdfPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structural tests for [drawSafePage] — the canvas save stack, and which native calls each
 * primitive issues.
 *
 * Harness: `drawSafePage` reaches through `drawContext.canvas.nativeCanvas`, a hard cast to
 * Compose's `AndroidCanvas`, so a fake *Compose* `Canvas` would fail with a
 * `ClassCastException`. Instead [SpyCanvas] subclasses the unit-test stub
 * `android.graphics.Canvas` and overrides the calls that matter; overrides in a subclass run
 * for real even though the stub bodies do not. `Canvas(spy)` wraps it back into a Compose
 * canvas and [CanvasDrawScope] supplies the `DrawScope`.
 *
 * Nothing rasterizes here, so these are assertions about calls and stack depth. Text
 * primitives are excluded: they call `Typeface.create`, which the stub android.jar answers
 * with null. The Text and pixel assertions live in [DrawSafePageTextTest].
 */
class DrawSafePageTest {

    private val pageWidth = 612f
    private val pageHeight = 792f

    /**
     * Mirrors `MAX_GROUP_LAYER_DEPTH` in SafePdfViewerScreen.kt, which is file-private. Rust
     * bounds group depth at 32 as well, so this is an agreed limit, not a local detail.
     */
    private val maxLayerDepth = 32

    /**
     * Records the native canvas calls [drawSafePage] issues, and models the save stack the way
     * a real [android.graphics.Canvas] does: a [restore] past the level the page was entered at
     * throws, because that is precisely the condition that corrupts everything drawn afterwards.
     */
    private class SpyCanvas : android.graphics.Canvas() {
        var depth = 0
        var maxDepth = 0
        var saves = 0
        var saveLayers = 0
        var restores = 0
        var clipPaths = 0
        var drawBitmaps = 0
        var drawPaths = 0

        /**
         * Level below which a [restore] is an over-pop into the caller's state. `CanvasDrawScope`
         * brackets the draw block in its own save/restore, so the floor is set once the block has
         * been entered rather than at construction.
         */
        private var floor = 0

        /**
         * Set once the page is done, so `CanvasDrawScope`'s own closing restore is neither counted
         * nor treated as an over-pop.
         */
        private var frozen = false

        /** Zero the counters at the point [drawSafePage] is entered. */
        fun baseline() {
            floor = depth
            frozen = false
            maxDepth = 0
            saves = 0
            saveLayers = 0
            restores = 0
            clipPaths = 0
            drawBitmaps = 0
            drawPaths = 0
        }

        /** Stop attributing calls to the page once it has returned. */
        fun releaseFloor() {
            floor = 0
            frozen = true
        }

        override fun save(): Int {
            depth++
            if (!frozen) {
                saves++
                if (depth - floor > maxDepth) maxDepth = depth - floor
            }
            return depth
        }

        override fun saveLayer(bounds: android.graphics.RectF?, paint: android.graphics.Paint?): Int {
            depth++
            if (!frozen) {
                saveLayers++
                if (depth - floor > maxDepth) maxDepth = depth - floor
            }
            return depth
        }

        override fun restore() {
            check(frozen || depth > floor) {
                "restore() below the level drawSafePage was entered at: an over-pop corrupts " +
                    "the clip and layer state of everything drawn after this page"
            }
            if (!frozen) restores++
            depth--
        }

        override fun getSaveCount(): Int = depth + 1

        override fun restoreToCount(saveCount: Int) {
            while (depth + 1 > saveCount) restore()
        }

        override fun clipPath(path: android.graphics.Path): Boolean {
            if (!frozen) clipPaths++
            return true
        }

        override fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean = true

        override fun drawPath(path: android.graphics.Path, paint: android.graphics.Paint) {
            if (!frozen) drawPaths++
        }

        override fun drawBitmap(
            bitmap: android.graphics.Bitmap,
            matrix: android.graphics.Matrix,
            paint: android.graphics.Paint?,
        ) {
            if (!frozen) drawBitmaps++
        }
    }

    /** Run [drawSafePage] over [prims] and return the spy that observed it. */
    private fun render(
        vararg prims: PdfPrimitive,
        canvasSize: Size = Size(pageWidth, pageHeight),
    ): SpyCanvas {
        val spy = SpyCanvas()
        val page = SafePdfPage(pageWidth, pageHeight, prims.toList())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(spy), canvasSize) {
            spy.baseline()
            drawSafePage(page)
            spy.releaseFloor()
        }
        return spy
    }

    /** An ink marker, used to prove the page was still being drawn at a given point. */
    private fun ink() = PdfPrimitive.FillPath(
        color = 0xFF123456.toInt(),
        evenOdd = false,
        contours = listOf(square()),
    )

    private fun square() = listOf(
        Offset(0f, 0f), Offset(100f, 0f), Offset(100f, 100f), Offset(0f, 100f),
    )

    // ---- canvas save-stack balance ----

    /**
     * An unbalanced save stack corrupts everything drawn after the page. Balanced brackets must
     * come back to exactly the level the page was entered at.
     */
    @Test
    fun balancedBracketsLeaveTheSaveStackWhereItStarted() {
        val spy = render(
            PdfPrimitive.ClipPush(false, square()),
            ink(),
            PdfPrimitive.ClipPop,
            PdfPrimitive.GroupPush(true, false, 1f, BlendMode.Normal),
            PdfPrimitive.GroupPop,
            PdfPrimitive.SoftMaskPush(0),
            PdfPrimitive.SoftMaskContent,
            PdfPrimitive.SoftMaskPop,
        )
        assertEquals(0, spy.depth, "save stack left ${spy.depth} level(s) open")
        assertEquals(spy.saves + spy.saveLayers, spy.restores)
    }

    /**
     * More pops than pushes must be absorbed, not passed through: an extra `restore()` would
     * unwind a level the *caller* owns. A real canvas throws on that underflow and so does the
     * spy, so this fails loudly if the guard is dropped.
     */
    @Test
    fun morePopsThanPushesNeverUnderflowsTheCallersStack() {
        val spy = render(
            PdfPrimitive.ClipPush(false, square()),
            PdfPrimitive.ClipPop,
            PdfPrimitive.ClipPop,
            PdfPrimitive.ClipPop,
            PdfPrimitive.GroupPop,
            PdfPrimitive.GroupPop,
            PdfPrimitive.SoftMaskPop,
            ink(),
        )
        assertEquals(0, spy.depth)
        assertEquals(1, spy.drawPaths, "the surplus pops aborted the rest of the page")
    }

    /** More pushes than pops must be drained on the way out, not left open. */
    @Test
    fun morePushesThanPopsAreDrainedOnExit() {
        val spy = render(
            PdfPrimitive.ClipPush(false, square()),
            PdfPrimitive.ClipPush(false, square()),
            PdfPrimitive.GroupPush(true, false, 1f, BlendMode.Normal),
            PdfPrimitive.SoftMaskPush(1),
            PdfPrimitive.SoftMaskContent,
            ink(),
        )
        assertEquals(0, spy.depth, "an unterminated bracket was left open")
        assertTrue(spy.maxDepth > 0, "the input never opened a level, so the test proves nothing")
    }

    /** Interleaved bracket kinds must each restore their own levels, never a sibling's. */
    @Test
    fun interleavedBracketKindsEachRestoreTheirOwnLevels() {
        val spy = render(
            PdfPrimitive.ClipPush(false, square()),
            PdfPrimitive.GroupPush(true, false, 0.5f, BlendMode.Multiply),
            PdfPrimitive.SoftMaskPush(1),
            PdfPrimitive.SoftMaskContent,
            PdfPrimitive.SoftMaskPop,
            PdfPrimitive.GroupPop,
            PdfPrimitive.ClipPop,
        )
        assertEquals(0, spy.depth)
        assertEquals(spy.saves + spy.saveLayers, spy.restores)
    }

    /**
     * A soft-mask bracket opens more than one level — its own layer, the DST_IN composite and,
     * for a luminosity mask, the luma layer — and the pop must restore exactly those.
     */
    @Test
    fun aLuminositySoftMaskRestoresEveryLevelItOpened() {
        val spy = render(
            PdfPrimitive.SoftMaskPush(1),
            ink(),
            PdfPrimitive.SoftMaskContent,
            ink(),
            PdfPrimitive.SoftMaskPop,
        )
        assertEquals(0, spy.depth)
        assertEquals(3, spy.maxDepth, "a luminosity mask needs its layer, DST_IN and luma levels")
    }

    /** An identity /TR on an alpha mask needs no extra colour-matrix level. */
    @Test
    fun anAlphaSoftMaskWithAnIdentityTransferOpensNoLumaLevel() {
        val spy = render(
            PdfPrimitive.SoftMaskPush(0),
            PdfPrimitive.SoftMaskContent,
            PdfPrimitive.SoftMaskPop,
        )
        assertEquals(0, spy.depth)
        assertEquals(2, spy.maxDepth)
    }

    /** A non-identity /TR on an alpha mask does, since gain/bias needs the colour matrix. */
    @Test
    fun aNonIdentityTransferOnAnAlphaMaskOpensTheExtraLevel() {
        val spy = render(
            PdfPrimitive.SoftMaskPush(0),
            PdfPrimitive.SoftMaskTransfer(gain = -1f, bias = 1f, affine = true),
            PdfPrimitive.SoftMaskContent,
            PdfPrimitive.SoftMaskPop,
        )
        assertEquals(0, spy.depth)
        assertEquals(3, spy.maxDepth)
    }

    /** A /TR the parser could not fit must be ignored, not applied as a wrong curve. */
    @Test
    fun aNonAffineTransferIsIgnored() {
        val spy = render(
            PdfPrimitive.SoftMaskPush(0),
            PdfPrimitive.SoftMaskTransfer(gain = 3f, bias = 0.4f, affine = false),
            PdfPrimitive.SoftMaskContent,
            PdfPrimitive.SoftMaskPop,
        )
        assertEquals(2, spy.maxDepth, "a non-affine /TR must not install a colour matrix layer")
    }

    /**
     * The group-layer depth cap must skip only the offscreen layer, never abandon the page. An
     * earlier version `break`ed out of the loop here, dropping the whole remainder of the page,
     * and the push must still be accounted so its pop restores the right level.
     */
    @Test
    fun theGroupDepthCapSkipsTheLayerButKeepsDrawingThePage() {
        val over = 3
        val prims = buildList {
            repeat(maxLayerDepth + over) { add(PdfPrimitive.GroupPush(true, false, 1f, BlendMode.Normal)) }
            add(ink())
            repeat(maxLayerDepth + over) { add(PdfPrimitive.GroupPop) }
            add(ink())
        }
        val spy = render(*prims.toTypedArray())
        assertEquals(
            over,
            spy.saves,
            "pushes past the cap must fall back to a plain save so their pops still balance",
        )
        assertEquals(maxLayerDepth, spy.saveLayers)
        assertEquals(0, spy.depth)
        assertEquals(2, spy.drawPaths, "tripping the depth cap abandoned the rest of the page")
    }

    /** Soft-mask nesting takes the same cap, and likewise must not abandon the page. */
    @Test
    fun theSoftMaskDepthCapKeepsDrawingThePage() {
        val prims = buildList {
            repeat(maxLayerDepth + 2) { add(PdfPrimitive.SoftMaskPush(0)) }
            add(ink())
            repeat(maxLayerDepth + 2) { add(PdfPrimitive.SoftMaskPop) }
        }
        val spy = render(*prims.toTypedArray())
        assertEquals(0, spy.depth)
        assertEquals(1, spy.drawPaths)
    }

    /**
     * `TextClipApply` always saves, because Rust incremented its clip depth and will send the
     * matching `ClipPop`. Skipping the save when no glyphs were accumulated would leave that pop
     * to release an enclosing clip early.
     *
     * It must also always CLIP. §9.4.3 combines the accumulated outlines with the current clip
     * by INTERSECTION, and an empty accumulation intersects to empty rather than to absent, so
     * a run that accumulated nothing narrows the clip to nothing. Saving and clipping to empty
     * are both satisfiable at once; treating "no outlines" as "no clip" is the one direction
     * that adds ink, and paints the following content over the whole page.
     */
    @Test
    fun textClipApplyWithNoAccumulatedGlyphsOpensALevelAndClipsToNothing() {
        val spy = render(
            PdfPrimitive.TextClipApply,
            PdfPrimitive.ClipPop,
        )
        assertEquals(1, spy.saves)
        assertEquals(1, spy.clipPaths, "an empty text clip must still narrow to nothing")
        assertEquals(0, spy.depth)
    }

    /**
     * A degenerate clip push must still save without narrowing the clip, so its pop pairs with
     * it instead of releasing an enclosing clip — content bleeding outside its box.
     */
    @Test
    fun aDegenerateClipPushSavesWithoutNarrowingTheClip() {
        val spy = render(
            PdfPrimitive.ClipPush(false, emptyList()),
            PdfPrimitive.ClipPop,
        )
        assertEquals(1, spy.saves)
        assertEquals(0, spy.clipPaths, "a degenerate push must not install a clip path")
        assertEquals(0, spy.depth)
    }

    /** A clip push with real geometry does install a clip path. */
    @Test
    fun aClipPushWithGeometryInstallsAClipPath() {
        val spy = render(
            PdfPrimitive.ClipPush(false, square()),
            PdfPrimitive.ClipPop,
        )
        assertEquals(1, spy.clipPaths)
    }

    /** A v4 bezier path is preferred over the flattened polyline for a curved clip. */
    @Test
    fun aClipPushPrefersTheBezierPathOverThePolyline() {
        val spy = render(
            PdfPrimitive.ClipPush(
                evenOdd = true,
                points = square(),
                pathOps = listOf(
                    com.vayunmathur.pdf.platform.PathOp.Move(0f, 0f),
                    com.vayunmathur.pdf.platform.PathOp.Cubic(1f, 1f, 2f, 2f, 3f, 3f),
                    com.vayunmathur.pdf.platform.PathOp.Close,
                ),
            ),
            PdfPrimitive.ClipPop,
        )
        assertEquals(1, spy.clipPaths)
        assertEquals(0, spy.depth)
    }

    // ---- primitives that cannot paint ----

    /** A fill with fewer than two points in every contour has nothing to draw. */
    @Test
    fun aFillWithNoUsableContourDrawsNothing() {
        val spy = render(
            PdfPrimitive.FillPath(0xFF000000.toInt(), false, listOf(listOf(Offset(1f, 1f)))),
        )
        assertEquals(0, spy.drawPaths)
    }

    /** A single-point stroke has no segment to paint and must not abort the page. */
    @Test
    fun aDegenerateStrokeIsSkippedWithoutAbortingThePage() {
        val spy = render(
            PdfPrimitive.StrokePath(
                color = 0xFF000000.toInt(), width = 1f, dash = FloatArray(0), dashPhase = 0f,
                points = listOf(Offset(1f, 1f)),
            ),
            ink(),
        )
        assertEquals(1, spy.drawPaths)
    }

    /** An image whose bitmap failed to decode must be skipped without touching the stack. */
    @Test
    fun anImageWithNoBitmapIsSkippedCleanly() {
        val spy = render(
            PdfPrimitive.Image(floatArrayOf(100f, 0f, 0f, 100f, 0f, 0f), bitmap = null),
            ink(),
        )
        assertEquals(0, spy.drawBitmaps)
        assertEquals(0, spy.depth)
        assertEquals(1, spy.drawPaths, "a bitmap-less image aborted the rest of the page")
    }

    @Test
    fun aTiledImageWithNoBitmapIsSkippedCleanly() {
        val spy = render(
            PdfPrimitive.ImageTiled(
                floatArrayOf(8f, 0f, 0f, 8f, 0f, 0f), bitmap = null,
                xstep = 8f, ystep = 8f, i0 = 0, j0 = 0, nx = 4, ny = 4,
            ),
            ink(),
        )
        assertEquals(1, spy.drawPaths, "only the marker fill should have been drawn")
    }

    /** An empty page must not disturb the save stack at all. */
    @Test
    fun anEmptyPageIssuesNothing() {
        val spy = render()
        assertEquals(0, spy.saves)
        assertEquals(0, spy.saveLayers)
        assertEquals(0, spy.restores)
    }

    /** A fill does reach the native canvas, so the ink marker used above is meaningful. */
    @Test
    fun aFillReachesTheNativeCanvas() {
        val spy = render(ink())
        assertEquals(1, spy.drawPaths)
    }
}
