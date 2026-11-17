package com.vayunmathur.pdf.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Image, clip, group and soft-mask primitive tests for [SafePdfParser].
 *
 * Split from [SafePdfParserTest] (LargeClass): header/text/fill/stroke and
 * robustness tests stay there; image and bracket primitives live here.
 */
class SafePdfParserImageTest {

    /**
     * The image payload is the only one whose length depends on the wire version, so it is
     * the one that desyncs the rest of the buffer if a gate is wrong. At v10 — what Rust
     * emits today — no `/Interpolate` byte is present, and a parser that read one anyway
     * would consume the next primitive's tag.
     */
    @Test
    fun theImagePayloadLengthTracksTheDeclaredVersion() {
        val v10 = SafePdfParser.parse(WireWriter(version = 10).image().fill().build())
        assertEquals(2, v10.primitives.size)
        assertTrue(assertIs<PdfPrimitive.Image>(v10.primitives[0]).interpolate, "pre-v11 defaults to smoothing")
        assertIs<PdfPrimitive.FillPath>(v10.primitives[1])

        val v11 = SafePdfParser.parse(WireWriter(version = 11).image(interpolate = false).fill().build())
        assertEquals(2, v11.primitives.size)
        assertTrue(!assertIs<PdfPrimitive.Image>(v11.primitives[0]).interpolate)
        assertIs<PdfPrimitive.FillPath>(v11.primitives[1])
    }

    @Test
    fun imageDecodesCtmAlphaAndBlend() {
        val ctm = floatArrayOf(100f, 1f, 2f, 200f, 30f, 40f)
        val page = SafePdfParser.parse(
            WireWriter().image(ctm = ctm, alpha = 0.5f, blend = 1).fill().build()
        )
        val img = assertIs<PdfPrimitive.Image>(page.primitives[0])
        assertContentEquals(ctm, img.ctm)
        assertEquals(0.5f, img.alpha)
        assertEquals(BlendMode.Multiply, img.blend)
    }

    @Test
    fun imageAlphaIsClampedToTheUnitRange() {
        val page = SafePdfParser.parse(WireWriter().image(alpha = 4f).image(alpha = -2f).build())
        assertEquals(1f, assertIs<PdfPrimitive.Image>(page.primitives[0]).alpha)
        assertEquals(0f, assertIs<PdfPrimitive.Image>(page.primitives[1]).alpha)
    }

    /**
     * A JPEG over the 16 Mpx budget must SURVIVE as a primitive. Rust deliberately drops its
     * own pixel guard for the format-1 passthrough (images.rs:1082-1087) and sends the photo
     * at full dimensions, because the platform decoder subsamples it. Kotlin used to apply the
     * budget in the TAG_IMAGE arm BEFORE reading `format`, so it rejected every format alike
     * and deleted the image with no bitmap, no log and nothing in logcat — the passthrough
     * Rust built to stop deleting high-res scans was cancelled one language later.
     *
     * 5184x3888 is an ordinary 20 MP camera photo, over the 16 Mi pixel cap.
     */
    @Test
    fun anOversizedJpegSurvivesInsteadOfBeingDropped() {
        val page = SafePdfParser.parse(
            WireWriter()
                .image(w = 5184, h = 3888, format = 1, data = ByteArray(64))
                .fill(argb = 0xFF121212.toInt())
                .build()
        )
        assertEquals(2, page.primitives.size, "the oversized JPEG primitive was dropped")
        assertIs<PdfPrimitive.Image>(page.primitives[0])
        // The stream must still be in sync for everything after it.
        assertEquals(0xFF121212.toInt(), assertIs<PdfPrimitive.FillPath>(page.primitives[1]).color)
    }

    /**
     * The counterpart, and an HONEST statement of what this harness can show. The raw path
     * must still refuse an oversized image, because it allocates `w*h` ints up front with no
     * chance to subsample; Rust decimates that path, so an oversized raw image is a contract
     * violation rather than ordinary input.
     *
     * The stub `android.jar` answers `BitmapFactory` with null for BOTH formats, so a null
     * bitmap here is NOT evidence that the format guard fired — this test pins that the
     * primitive survives and the stream stays in sync, which is what is observable off-device.
     * The guard itself is pinned by [theJpegSubsampleFactorIsAPowerOfTwoThatMeetsTheBudget]
     * and by the format-1 branch being the only one that reaches a decoder at all.
     */
    @Test
    fun anOversizedRawImageKeepsItsPrimitiveAndTheStreamInSync() {
        val page = SafePdfParser.parse(
            WireWriter()
                .image(w = 5184, h = 3888, format = 0, data = ByteArray(64))
                .fill(argb = 0xFF343434.toInt())
                .build()
        )
        assertEquals(2, page.primitives.size)
        assertNull(assertIs<PdfPrimitive.Image>(page.primitives[0]).bitmap)
        assertEquals(0xFF343434.toInt(), assertIs<PdfPrimitive.FillPath>(page.primitives[1]).color)
    }

    /**
     * `BitmapFactory` rounds `inSampleSize` DOWN to a power of two, so a non-power-of-two would
     * decode LARGER than requested — the direction that OOMs. Pin that the returned factor is
     * always a power of two AND actually brings the image under budget.
     */
    @Test
    fun theJpegSubsampleFactorIsAPowerOfTwoThatMeetsTheBudget() {
        val cap = 16L * 1024L * 1024L
        val cases = listOf(
            2 to 2,             // trivially under budget
            4096 to 4096,       // exactly at the cap
            5184 to 3888,       // 20 MP photo
            9933 to 14043,      // A0 300dpi scan, 139 Mpx
            20000 to 20000,     // the dimension bound, 400 Mpx
        )
        for ((w, h) in cases) {
            val s = SafePdfParser.sampleSizeFor(w, h)
            assertTrue(s > 0 && (s and (s - 1)) == 0, "sample size $s for ${w}x$h is not a power of two")
            assertTrue(
                (w.toLong() / s) * (h.toLong() / s) <= cap,
                "sample size $s leaves ${w}x$h over the pixel budget",
            )
        }
        assertEquals(1, SafePdfParser.sampleSizeFor(2, 2), "a small image must not be subsampled")
        assertEquals(1, SafePdfParser.sampleSizeFor(4096, 4096), "an image at the cap must not be subsampled")
    }

    /**
     * An unusable size must consume its payload EXACTLY and drop only that primitive; a
     * short-read here would desync every primitive after it.
     */
    @Test
    fun anImageWithUnusableDimensionsIsSkippedWithoutDesyncingTheStream() {
        val page = SafePdfParser.parse(
            WireWriter().image(w = 0, h = 0, data = ByteArray(64)).fill(argb = 0xFF121212.toInt()).build()
        )
        val fill = assertIs<PdfPrimitive.FillPath>(page.primitives.single())
        assertEquals(0xFF121212.toInt(), fill.color)
    }

    /**
     * An oversized raster payload is NOT a corrupt buffer, and treating it as one blanks the
     * rest of the page.
     *
     * Rust's producer bound is looser than the 16 MB this decoder is willing to materialise:
     * `extract_inline_image` only enforces `MAX_IMAGE_PIXELS` (16 MP, i.e. 64 MB of RGBA) and
     * the format-1 JPEG passthrough forwards the stream bytes with a 64 MB ceiling. A 20 MB
     * photo scan is therefore an ordinary, well-formed stream. The length field says exactly
     * how many bytes to step over, so the decoder must drop that one image and carry on —
     * ending the page instead would lose every LATER primitive as well.
     */
    @Test
    fun anOversizedImagePayloadDropsOnlyThatImage() {
        val page = SafePdfParser.parse(
            WireWriter()
                .image(w = 8, h = 8, data = ByteArray(16 * 1024 * 1024 + 1))
                .fill(argb = 0xFF191919.toInt())
                .stroke(argb = 0xFF282828.toInt())
                .build()
        )
        assertEquals(
            2,
            page.primitives.size,
            "an over-sized image must cost one primitive, not the rest of the page",
        )
        assertEquals(0xFF191919.toInt(), assertIs<PdfPrimitive.FillPath>(page.primitives[0]).color)
        assertEquals(0xFF282828.toInt(), assertIs<PdfPrimitive.StrokePath>(page.primitives[1]).color)
    }

    /** Same contract for a tiling-pattern cell (tag 14). */
    @Test
    fun anOversizedTilingCellDropsOnlyThatPattern() {
        val page = SafePdfParser.parse(
            WireWriter()
                .imageTiled(w = 4, h = 4, data = ByteArray(16 * 1024 * 1024 + 1))
                .fill(argb = 0xFF373737.toInt())
                .build()
        )
        assertEquals(0xFF373737.toInt(), assertIs<PdfPrimitive.FillPath>(page.primitives.single()).color)
    }

    /**
     * A payload the buffer cannot actually contain is a different case: there is nothing to
     * skip to, so the page keeps its clean prefix and stops. This pins the two apart, so the
     * skip above cannot be widened into "ignore truncation".
     */
    @Test
    fun anImagePayloadLongerThanTheBufferStopsAfterThePrefix() {
        val whole = WireWriter().fill(argb = 0xFF464646.toInt()).image(data = ByteArray(64)).build()
        val page = SafePdfParser.parse(whole.copyOf(whole.size - 32))
        assertEquals(0xFF464646.toInt(), assertIs<PdfPrimitive.FillPath>(page.primitives.single()).color)
    }

    @Test
    fun imageTiledDecodesTheLatticeAndCell() {
        val ctm = floatArrayOf(8f, 0f, 0f, 8f, 5f, 6f)
        val page = SafePdfParser.parse(
            WireWriter().imageTiled(
                ctm = ctm, xstep = 9f, ystep = 11f, i0 = -3, j0 = -4, nx = 7, ny = 8,
                alpha = 0.25f, blend = 1,
            ).build()
        )
        val t = assertIs<PdfPrimitive.ImageTiled>(page.primitives.single())
        assertContentEquals(ctm, t.ctm)
        assertEquals(9f, t.xstep)
        assertEquals(11f, t.ystep)
        assertEquals(-3, t.i0)
        assertEquals(-4, t.j0)
        assertEquals(7, t.nx)
        assertEquals(8, t.ny)
        assertEquals(0.25f, t.alpha)
        assertEquals(BlendMode.Multiply, t.blend)
    }

    /** An absurd lattice extent must clamp, so the region path cannot get non-finite coords. */
    @Test
    fun anAbsurdLatticeExtentIsClamped() {
        val page = SafePdfParser.parse(
            WireWriter().imageTiled(nx = Int.MAX_VALUE, ny = -5).build()
        )
        val t = assertIs<PdfPrimitive.ImageTiled>(page.primitives.single())
        assertEquals(100_000, t.nx)
        assertEquals(0, t.ny)
    }

    @Test
    fun clipPushCarriesBothThePolylineAndTheBezierPath() {
        val ops = listOf(
            PathOp.Move(1f, 2f),
            PathOp.Line(3f, 4f),
            PathOp.Cubic(5f, 6f, 7f, 8f, 9f, 10f),
            PathOp.Close,
        )
        val page = SafePdfParser.parse(
            WireWriter().clipPush(evenOdd = true, pts = WireWriter.square(), pathOps = ops).build()
        )
        val c = assertIs<PdfPrimitive.ClipPush>(page.primitives.single())
        assertTrue(c.evenOdd)
        assertEquals(4, c.points.size)
        assertEquals(ops, c.pathOps)
    }

    /**
     * Rust increments its clip depth for a degenerate push and still sends the matching pop,
     * so dropping the push here would let that pop release an ENCLOSING clip early.
     */
    @Test
    fun aDegenerateClipPushIsStillEmitted() {
        val page = SafePdfParser.parse(
            WireWriter().clipPush(pts = emptyList()).clipPop().build()
        )
        assertEquals(2, page.primitives.size)
        val c = assertIs<PdfPrimitive.ClipPush>(page.primitives[0])
        assertTrue(c.points.isEmpty())
        assertEquals(PdfPrimitive.ClipPop, page.primitives[1])
    }

    @Test
    fun groupPushDecodesFlagsAlphaAndBlend() {
        val page = SafePdfParser.parse(
            WireWriter().groupPush(isolated = true, knockout = true, alpha = 0.5f, blend = 15).build()
        )
        val g = assertIs<PdfPrimitive.GroupPush>(page.primitives.single())
        assertTrue(g.isolated)
        assertTrue(g.knockout)
        assertEquals(0.5f, g.alpha)
        assertEquals(BlendMode.Luminosity, g.blend)
    }

    @Test
    fun softMaskPushCarriesTheMaskType() {
        val page = SafePdfParser.parse(
            WireWriter().softMaskPush(1).softMaskContent().softMaskPop().build()
        )
        assertEquals(1, assertIs<PdfPrimitive.SoftMaskPush>(page.primitives[0]).maskType)
    }

    // ---- /TR transfer function fitting ----

    /** An identity /TR must fit exactly as gain 1, bias 0 — the mask passes through. */
    @Test
    fun anIdentityTransferFitsAsIdentity() {
        val lut = ByteArray(256) { it.toByte() }
        val tr = fitOf(lut)
        assertTrue(tr.affine)
        assertEquals(1f, tr.gain, 1e-4f)
        assertEquals(0f, tr.bias, 1e-4f)
    }

    /**
     * An inverting /TR is the standard "mask out where the group is bright" idiom, so getting
     * this wrong hides exactly the wrong half of the group.
     */
    @Test
    fun anInvertingTransferFitsAsNegativeGain() {
        val lut = ByteArray(256) { (255 - it).toByte() }
        val tr = fitOf(lut)
        assertTrue(tr.affine)
        assertEquals(-1f, tr.gain, 1e-4f)
        assertEquals(1f, tr.bias, 1e-4f)
    }

    @Test
    fun aConstantTransferFitsAsZeroGain() {
        val lut = ByteArray(256) { 128.toByte() }
        val tr = fitOf(lut)
        assertTrue(tr.affine)
        assertEquals(0f, tr.gain, 1e-4f)
        assertEquals(128f / 255f, tr.bias, 1e-4f)
    }

    /**
     * A curve no straight line can represent must be reported non-affine and left alone; the
     * documented choice is to skip it rather than approximate it, because a wrong curve hides
     * the wrong half of the group.
     */
    @Test
    fun aStepTransferIsReportedNonAffine() {
        val lut = ByteArray(256) { if (it < 128) 0 else 255.toByte() }
        assertTrue(!fitOf(lut).affine)
    }

    private fun fitOf(lut: ByteArray): PdfPrimitive.SoftMaskTransfer {
        val page = SafePdfParser.parse(
            WireWriter().softMaskPush().softMaskTransfer(lut).softMaskContent().softMaskPop().build()
        )
        return assertIs(page.primitives[1])
    }

}
