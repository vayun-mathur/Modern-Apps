package com.vayunmathur.pdf.platform

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wire-decoding tests for [SafePdfParser].
 *
 * Byte buffers are built by [WireWriter], transcribed from `wire::serialize` in
 * `pdf/src/main/rust/src/wire.rs`, so a field that moves on one side and not the other
 * shows up here as a decode mismatch rather than as a silently garbled page.
 */
class SafePdfParserTest {

    // ---- header and version compatibility ----

    /**
     * Rust greps these two declarations out of the Kotlin source
     * (`wire::tests::wire_version_is_not_ahead_of_the_kotlin_parser`). Pinning them here
     * means a rename or reformat that breaks that grep fails a Kotlin test too, instead of
     * silently disabling the cross-language check.
     */
    @Test
    fun wireConstantsAreThePinnedValues() {
        assertEquals(0x50444657, SafePdfParser.WIRE_MAGIC)
        assertEquals(11, SafePdfParser.WIRE_VERSION)
    }

    /**
     * Rust declares `WIRE_VERSION = 10` and Kotlin understands up to 11. Every tag must
     * decode from the version Rust actually emits — a mishandled version renders every page
     * of every document blank.
     */
    @Test
    fun theVersionRustActuallyEmitsDecodesEveryTag() {
        val page = SafePdfParser.parse(
            WireWriter(version = 10)
                .text(renderMode = 2)
                .fill()
                .stroke(dash = floatArrayOf(3f, 2f))
                .image()
                .imageTiled()
                .clipPush()
                .clipPop()
                .groupPush()
                .groupPop()
                .textClipApply()
                .softMaskPush()
                .softMaskContent()
                .softMaskPop()
                .build()
        )
        assertEquals(13, page.primitives.size)
        assertIs<PdfPrimitive.Text>(page.primitives[0])
        assertIs<PdfPrimitive.FillPath>(page.primitives[1])
        assertIs<PdfPrimitive.StrokePath>(page.primitives[2])
        assertIs<PdfPrimitive.Image>(page.primitives[3])
        assertIs<PdfPrimitive.ImageTiled>(page.primitives[4])
        assertIs<PdfPrimitive.ClipPush>(page.primitives[5])
        assertEquals(PdfPrimitive.ClipPop, page.primitives[6])
        assertIs<PdfPrimitive.GroupPush>(page.primitives[7])
        assertEquals(PdfPrimitive.GroupPop, page.primitives[8])
        assertEquals(PdfPrimitive.TextClipApply, page.primitives[9])
        assertIs<PdfPrimitive.SoftMaskPush>(page.primitives[10])
        assertEquals(PdfPrimitive.SoftMaskContent, page.primitives[11])
        assertEquals(PdfPrimitive.SoftMaskPop, page.primitives[12])
    }

    @Test
    fun pageDimensionsComeFromTheHeader() {
        val page = SafePdfParser.parse(WireWriter(width = 595f, height = 842f).fill().build())
        assertEquals(595f, page.width)
        assertEquals(842f, page.height)
    }

    /** A legacy v1 buffer has no magic; the first f32 is the width. */
    @Test
    fun legacyV1HeaderIsReinterpretedAsWidth() {
        val page = SafePdfParser.parse(
            WireWriter(version = 1, width = 300f, height = 400f, legacyV1 = true).fill().build()
        )
        assertEquals(300f, page.width)
        assertEquals(400f, page.height)
        // v1 gates every later field off: one contour read as a bare polyline, no blend byte.
        val fill = assertIs<PdfPrimitive.FillPath>(page.primitives.single())
        assertEquals(1, fill.contours.size)
        assertEquals(BlendMode.Normal, fill.blend)
    }

    /**
     * A version outside the known range must warn and carry on, never throw, in EITHER
     * direction. A throw here becomes a null page and an indefinite spinner, and the
     * older-than-known case degrades exactly as gracefully as the newer-than-known one:
     * parse what the tags allow.
     */
    @Test
    fun aFutureWireVersionIsToleratedRatherThanRejected() {
        val page = SafePdfParser.parse(WireWriter(version = 99).fill().build())
        assertEquals(1, page.primitives.size)
    }

    @Test
    fun versionZeroIsToleratedRatherThanRejected() {
        val page = SafePdfParser.parse(WireWriter(version = 0).fill().build())
        assertEquals(612f, page.width)
        assertEquals(792f, page.height)
    }

    /**
     * Out-of-range dimensions are CLAMPED, not rejected. A throw happens before the
     * per-primitive guard, so it discards the whole page, and `renderPage` can only turn that
     * into a failed page — which the viewer used to show as an indefinite spinner. The Rust
     * producer carries no matching 20000pt bound, so it can legitimately emit a page this side
     * once refused: large-format CAD and poster PDFs land here and every real viewer renders
     * them. A page drawn at a clamped size beats a page that never appears.
     */
    @Test
    fun implausiblePageDimensionsAreClampedRatherThanRejected() {
        val zeroWidth = SafePdfParser.parse(WireWriter(width = 0f).fill().build())
        assertEquals(612f, zeroWidth.width, "a non-positive width must fall back, not throw")
        assertEquals(1, zeroWidth.primitives.size, "the page was discarded over its header")

        val tall = SafePdfParser.parse(WireWriter(height = 50_000f).fill().build())
        assertEquals(20000f, tall.height, "an over-tall page must clamp to the bound")
        assertEquals(1, tall.primitives.size)
    }

    /**
     * NaN is not caught by a range check — every comparison against it is false, so a NaN
     * dimension passes both `<= 0f` and `> 20000f`. It must not reach `SafePdfPageCanvas`,
     * where `Modifier.aspectRatio(width / height)` requires a ratio > 0 and throws from
     * COMPOSITION, losing the whole viewer rather than the one page. Infinity is caught by
     * the upper bound already; assert both so the guard cannot regress to a bare range test.
     */
    @Test
    fun nonFinitePageDimensionsAreReplacedWithFiniteOnes() {
        val nanWidth = SafePdfParser.parse(WireWriter(width = Float.NaN).fill().build())
        assertEquals(612f, nanWidth.width)
        assertTrue(nanWidth.width.isFinite() && nanWidth.height.isFinite())

        val nanHeight = SafePdfParser.parse(WireWriter(height = Float.NaN).fill().build())
        assertEquals(792f, nanHeight.height)

        val infWidth = SafePdfParser.parse(WireWriter(width = Float.POSITIVE_INFINITY).fill().build())
        assertEquals(612f, infWidth.width, "infinity is not finite, so it takes the fallback")
        assertTrue(infWidth.width > 0f, "aspectRatio() would throw from composition otherwise")
    }

    /**
     * A non-finite float must not reach a Paint or a selection rectangle. The usual Kotlin
     * range guards do not stop them: `coerceIn`/`coerceAtLeast` are `if (this < min) ...
     * else this`, and every comparison against NaN is false, so NaN passes straight through
     * the renderer's clamps; infinity likewise survives `advance > 0f` and then scales a
     * whole run's selection geometry to infinity.
     *
     * All three are reachable from ordinary PDF syntax via an overflowing real literal —
     * `size` most directly, since an overflowed `Tf` times a zero-scale matrix is
     * `inf * 0` = NaN — and all three are trivially reachable from a desynced buffer, which
     * is the case the decoder actually has to survive.
     */
    @Test
    fun nonFiniteTextScalarsFallBackToTheirDefaults() {
        fun textOf(size: Float = 12f, advance: Float = 6f, hScale: Float = 1f, strokeArgb: Int? = null, strokeWidth: Float = 0f) =
            assertIs<PdfPrimitive.Text>(
                SafePdfParser.parse(
                    WireWriter().text(
                        size = size, advance = advance, hScale = hScale,
                        strokeArgb = strokeArgb, strokeWidth = strokeWidth, text = "ab",
                    ).build()
                ).primitives.single()
            )

        assertEquals(1f, textOf(hScale = Float.NaN).hScale, "NaN must not reach Paint.textScaleX")
        assertEquals(1f, textOf(hScale = Float.POSITIVE_INFINITY).hScale)
        assertEquals(4f, textOf(hScale = 4f).hScale, "real anisotropy must pass through")

        assertEquals(0f, textOf(size = Float.NaN).size, "NaN must not reach Paint.textSize")
        // With size sanitized to 0 the pre-v7 advance heuristic derived from it is 0 too,
        // which is inert rather than infectious.
        assertEquals(0f, textOf(size = Float.NaN, advance = Float.NaN).advance)

        // A non-finite advance falls back to the documented size*0.5*len heuristic, not to
        // zero and not to infinity.
        assertEquals(12f * 0.5f * 2, textOf(size = 12f, advance = Float.POSITIVE_INFINITY).advance)
        assertEquals(12f * 0.5f * 2, textOf(size = 12f, advance = Float.NaN).advance)
        assertEquals(6f, textOf(advance = 6f).advance, "a real advance must pass through")

        assertEquals(
            0f,
            textOf(strokeArgb = 0xFF00FF00.toInt(), strokeWidth = Float.NaN).strokeWidth,
            "NaN must not reach Paint.strokeWidth",
        )
    }

    /**
     * A non-finite ORIGIN has no sane default, so the primitive is dropped rather than
     * defaulted — it would paint nothing yet still contribute a selection rectangle whose
     * distance comparisons are all false. The neighbours must survive, proving the whole
     * primitive was consumed and the stream stayed in sync.
     */
    @Test
    fun aTextPrimitiveWithANonFiniteOriginIsDroppedWithoutDesyncingTheStream() {
        val page = SafePdfParser.parse(
            WireWriter()
                .text(text = "before")
                .text(x = Float.NaN, text = "bad")
                .text(y = Float.POSITIVE_INFINITY, text = "alsobad")
                .text(text = "after")
                .build()
        )
        assertEquals(
            listOf("before", "after"),
            page.primitives.filterIsInstance<PdfPrimitive.Text>().map { it.text },
        )
    }

    @Test
    fun aBufferTooSmallForAHeaderIsRejected() {
        assertFailsWith<IllegalArgumentException> { SafePdfParser.parse(ByteArray(4)) }
    }

    // ---- per-tag decoding ----

    @Test
    fun textDecodesEveryV11Field() {
        val page = SafePdfParser.parse(
            WireWriter()
                .text(
                    x = 12.5f, y = 34.25f, size = 18f, argb = 0xFF203040.toInt(), text = "Hi \u00e9",
                    strokeArgb = 0xFF010203.toInt(), strokeWidth = 1.5f, renderMode = 2, blend = 1,
                    advance = 42.5f, bold = true, italic = true, family = 2, outline = true, hScale = 0.75f,
                )
                .build()
        )
        val t = assertIs<PdfPrimitive.Text>(page.primitives.single())
        assertEquals(Offset(12.5f, 34.25f), t.origin)
        assertEquals(18f, t.size)
        assertEquals(0xFF203040.toInt(), t.color)
        assertEquals("Hi \u00e9", t.text)
        assertEquals(0xFF010203.toInt(), t.strokeColor)
        assertEquals(1.5f, t.strokeWidth)
        assertEquals(2, t.renderMode)
        assertEquals(BlendMode.Multiply, t.blend)
        assertEquals(42.5f, t.advance)
        assertTrue(t.isBold)
        assertTrue(t.isItalic)
        assertEquals(2, t.fontFamily)
        assertTrue(t.outline)
        assertEquals(0.75f, t.hScale)
    }

    /**
     * Rust writes a zeroed stroke triple when there is no stroke, and the `hasStroke` flag is
     * what distinguishes that from a real black hairline stroke.
     */
    @Test
    fun textWithoutTheStrokeFlagHasNoStrokeColour() {
        val page = SafePdfParser.parse(WireWriter().text(strokeArgb = null).build())
        val t = assertIs<PdfPrimitive.Text>(page.primitives.single())
        assertNull(t.strokeColor)
        assertEquals(0f, t.strokeWidth)
    }

    /** The font-flag bit layout: bit 0 bold, bit 1 italic, bits 2-3 family, bit 4 outline. */
    @Test
    fun fontFlagBitsAreUnpackedIndependently() {
        fun flags(bold: Boolean, italic: Boolean, family: Int, outline: Boolean): PdfPrimitive.Text {
            val page = SafePdfParser.parse(
                WireWriter().text(bold = bold, italic = italic, family = family, outline = outline).build()
            )
            return assertIs(page.primitives.single())
        }
        flags(bold = true, italic = false, family = 0, outline = false).let {
            assertTrue(it.isBold); assertTrue(!it.isItalic); assertEquals(0, it.fontFamily); assertTrue(!it.outline)
        }
        flags(bold = false, italic = true, family = 1, outline = false).let {
            assertTrue(!it.isBold); assertTrue(it.isItalic); assertEquals(1, it.fontFamily)
        }
        flags(bold = false, italic = false, family = 0, outline = true).let {
            assertTrue(it.outline); assertEquals(0, it.fontFamily)
        }
    }

    /** Before v7 there is no advance on the wire, so the heuristic must fill in. */
    @Test
    fun preV7TextFallsBackToTheAdvanceHeuristic() {
        val page = SafePdfParser.parse(WireWriter(version = 6).text(size = 10f, text = "abcd").build())
        val t = assertIs<PdfPrimitive.Text>(page.primitives.single())
        assertEquals(10f * 0.5f * 4, t.advance)
    }

    @Test
    fun aBlendCodeOutsideTheEnumFallsBackToNormal() {
        val page = SafePdfParser.parse(WireWriter().text(blend = 200).build())
        val t = assertIs<PdfPrimitive.Text>(page.primitives.single())
        assertEquals(BlendMode.Normal, t.blend)
    }

    /**
     * Rust sends the blend mode as the PDF 32000-1 §11.3.5 index, so every code 0..15 must
     * round-trip to a distinct mode. A duplicated or missing entry silently substitutes the
     * wrong separable blend for a whole class of transparency groups, and because the fallback
     * is `Normal` a gap looks exactly like "no blending requested".
     */
    @Test
    fun everyBlendCodeOnTheWireDecodesToItsOwnMode() {
        val decoded = (0..15).map { code ->
            val page = SafePdfParser.parse(WireWriter().text(blend = code).build())
            assertIs<PdfPrimitive.Text>(page.primitives.single()).blend
        }
        assertEquals(16, decoded.toSet().size, "two wire blend codes decoded to the same mode")
        decoded.forEachIndexed { code, mode -> assertEquals(code, mode.code) }
    }

    /**
     * v6 carries a contour count so holes and glyph counters arrive as separate contours of
     * one path; flattening them into a single contour would fill the holes in.
     */
    @Test
    fun fillDecodesMultipleContours() {
        val outer = listOf(0f to 0f, 20f to 0f, 20f to 20f, 0f to 20f)
        val hole = listOf(5f to 5f, 15f to 5f, 15f to 15f, 5f to 15f)
        val page = SafePdfParser.parse(
            WireWriter().fill(argb = 0xFFAABBCC.toInt(), evenOdd = true, contours = listOf(outer, hole), blend = 4)
                .build()
        )
        val f = assertIs<PdfPrimitive.FillPath>(page.primitives.single())
        assertEquals(0xFFAABBCC.toInt(), f.color)
        assertTrue(f.evenOdd)
        assertEquals(2, f.contours.size)
        assertEquals(Offset(5f, 5f), f.contours[1][0])
        assertEquals(BlendMode.Darken, f.blend)
    }

    @Test
    fun strokeDecodesDashCapJoinAndMiter() {
        val page = SafePdfParser.parse(
            WireWriter().stroke(
                argb = 0xFF00FF00.toInt(), width = 3.5f, dash = floatArrayOf(4f, 2f, 1f),
                dashPhase = 1.25f, cap = 1, join = 2, miter = 4f,
                pts = listOf(1f to 2f, 3f to 4f, 5f to 6f), blend = 2,
            ).build()
        )
        val s = assertIs<PdfPrimitive.StrokePath>(page.primitives.single())
        assertEquals(0xFF00FF00.toInt(), s.color)
        assertEquals(3.5f, s.width)
        assertContentEquals(floatArrayOf(4f, 2f, 1f), s.dash)
        assertEquals(1.25f, s.dashPhase)
        assertEquals(1, s.cap)
        assertEquals(2, s.join)
        assertEquals(4f, s.miter)
        assertEquals(3, s.points.size)
        assertEquals(Offset(5f, 6f), s.points[2])
        assertEquals(BlendMode.Screen, s.blend)
    }

    // ---- robustness: the page must survive a bad stream ----

    /**
     * An unknown tag means the read position is already inside a payload, so every later tag
     * byte is random data that decodes into plausible-looking garbage. Keep the clean prefix
     * and stop; do NOT keep parsing from a desynced offset.
     */
    @Test
    fun anUnknownTagTruncatesInsteadOfManufacturingGarbage() {
        val page = SafePdfParser.parse(
            WireWriter()
                .fill(argb = 0xFF010101.toInt())
                .rawTag(200)
                .fill(argb = 0xFF020202.toInt())
                .build()
        )
        val fill = assertIs<PdfPrimitive.FillPath>(page.primitives.single())
        assertEquals(0xFF010101.toInt(), fill.color)
    }

    /**
     * The count is a backstop against a corrupt header field, not a truncation policy: over
     * the cap it must clamp and render a partial page, because a throw discards the page.
     */
    @Test
    fun aCountOverTheCapClampsRatherThanDiscardingThePage() {
        val page = SafePdfParser.parse(
            WireWriter().fill().fill().apply { declaredCount = SafePdfParser.MAX_PRIMITIVES + 7 }.build()
        )
        assertEquals(2, page.primitives.size)
    }

    /** A count larger than the buffer holds must stop at the end, not throw. */
    @Test
    fun aCountLargerThanTheBufferStopsCleanly() {
        val page = SafePdfParser.parse(
            WireWriter().fill().fill().apply { declaredCount = 500 }.build()
        )
        assertEquals(2, page.primitives.size)
    }

    /**
     * A negative count coerces to zero rather than throwing: the header is still usable, so
     * the page renders empty instead of becoming a failed page behind a spinner.
     */
    @Test
    fun aNegativeCountYieldsAnEmptyPageRatherThanAThrow() {
        val page = SafePdfParser.parse(WireWriter().fill().apply { declaredCount = -1 }.build())
        assertEquals(0, page.primitives.size)
        assertEquals(612f, page.width)
    }

    /**
     * The header guards must never be able to fail the whole page. Every rejection they used to
     * make became a null page, and the viewer rendered that as an indefinite spinner — so a
     * poster-sized page, a stale wire version and a corrupt count all presented as "still
     * loading" forever. Pin the whole class: a well-formed body always survives its header.
     */
    @Test
    fun noHeaderValueCanTurnAWellFormedPageIntoAFailedOne() {
        val headers = listOf(
            "over-wide" to WireWriter(width = 90_000f),
            "over-tall" to WireWriter(height = 90_000f),
            "zero width" to WireWriter(width = 0f),
            "negative height" to WireWriter(height = -5f),
            "NaN width" to WireWriter(width = Float.NaN),
            "infinite height" to WireWriter(height = Float.POSITIVE_INFINITY),
            "version below range" to WireWriter(version = 0),
            "version above range" to WireWriter(version = 99),
        )
        for ((label, writer) in headers) {
            val page = SafePdfParser.parse(writer.fill().build())
            assertEquals(1, page.primitives.size, "$label lost the page body")
            assertTrue(page.width.isFinite() && page.width > 0f, "$label left an unusable width")
            assertTrue(page.height.isFinite() && page.height > 0f, "$label left an unusable height")
            assertTrue(page.width <= 20000f && page.height <= 20000f, "$label exceeded the bound")
        }
    }

    /** A truncated /TR tail keeps the prefix rather than losing the page over one primitive. */
    @Test
    fun aTruncatedTransferLutTruncatesThePage() {
        val full = WireWriter().fill(argb = 0xFF030303.toInt()).softMaskTransfer(ByteArray(256)).build()
        val page = SafePdfParser.parse(full.copyOf(full.size - 100))
        val fill = assertIs<PdfPrimitive.FillPath>(page.primitives.single())
        assertEquals(0xFF030303.toInt(), fill.color)
    }

    /**
     * A page that ends inside a soft-mask bracket renders actively WRONG, not merely partial:
     * with no mask ever composited the masked content draws fully opaque, so a vignette
     * becomes a hard block. Trim back to the bracket.
     */
    @Test
    fun aPageEndingInsideASoftMaskBracketIsTrimmedBackToIt() {
        val page = SafePdfParser.parse(
            WireWriter().fill().fill().fill().softMaskPush().fill().build()
        )
        assertEquals(3, page.primitives.size)
        assertTrue(page.primitives.all { it is PdfPrimitive.FillPath })
    }

    /** Unless trimming would cost most of the page, in which case the partial mask stays. */
    @Test
    fun anUnterminatedBracketSpanningMostOfThePageIsKept() {
        val page = SafePdfParser.parse(
            WireWriter().softMaskPush().fill().fill().fill().build()
        )
        assertEquals(4, page.primitives.size)
        assertIs<PdfPrimitive.SoftMaskPush>(page.primitives[0])
    }

    /** A balanced bracket is never trimmed. */
    @Test
    fun aBalancedSoftMaskBracketSurvives() {
        val page = SafePdfParser.parse(
            WireWriter().softMaskPush().fill().softMaskContent().fill().softMaskPop().build()
        )
        assertEquals(5, page.primitives.size)
    }

    /**
     * The robustness contract on [SafePdfParser]: a buffer cut off mid-primitive must keep the
     * prefix that decoded cleanly. [SafePdfDocument.renderPage] can only turn a throw into a
     * null page — an indefinite spinner — so throwing here loses a page that was almost
     * entirely readable. Tag 13 already chose `break` for this case; the per-field throws now
     * funnel into the same path.
     */
    @Test
    fun aBufferCutMidPrimitiveKeepsThePrefixInsteadOfLosingThePage() {
        val full = WireWriter().fill(argb = 0xFF040404.toInt()).text(text = "hello world").build()
        val page = SafePdfParser.parse(full.copyOf(full.size - 6))
        val fill = assertIs<PdfPrimitive.FillPath>(page.primitives.single())
        assertEquals(0xFF040404.toInt(), fill.color)
    }

    /**
     * The same for a primitive whose very first fixed field is cut off, which raises
     * `BufferUnderflowException` from an unguarded relative get rather than the parser's own
     * `IllegalArgumentException`. Both must truncate, not propagate.
     */
    @Test
    fun aBufferCutAtAPrimitiveHeaderAlsoKeepsThePrefix() {
        val full = WireWriter().fill(argb = 0xFF050505.toInt()).stroke().build()
        // Leave the stroke's tag byte in place but almost none of its payload.
        val page = SafePdfParser.parse(full.copyOf(full.size - 30))
        val fill = assertIs<PdfPrimitive.FillPath>(page.primitives.single())
        assertEquals(0xFF050505.toInt(), fill.color)
    }

    @Test
    fun anEmptyPageDecodesToNoPrimitives() {
        val page = SafePdfParser.parse(WireWriter().build())
        assertEquals(0, page.primitives.size)
    }
}
