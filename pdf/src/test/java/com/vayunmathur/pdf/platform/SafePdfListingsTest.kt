package com.vayunmathur.pdf.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Listing-buffer and wire-size tests for [SafePdfParser].
 *
 * Split from [SafePdfParserTest] (LargeClass): page-primitive decoding stays there,
 * listing buffers and byte-count guards live here.
 */
class SafePdfListingsTest {


    // ---- listing buffers ----

    @Test
    fun readStringRejectsALengthTheBufferCannotHold() {
        val bytes = java.nio.ByteBuffer.allocate(4 + 8 + 1 + 16 + 4 + 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(1).putLong(7L).put(2)
            .putFloat(0f).putFloat(0f).putFloat(1f).putFloat(1f)
            .putInt(0)
            .putShort(9999)
            .array()
        assertFailsWith<IllegalArgumentException> { SafePdfParser.parseAnnotations(bytes) }
    }

    /**
     * Rust truncates every listing string at `u16::MAX`, not at 4096, so a long annotation
     * comment or multi-line form value is a well-formed record. Rejecting it threw straight
     * out of `SafePdfDocument.annotations`, which does not catch, so one long sticky note
     * took down the whole listing.
     */
    @Test
    fun aListingStringLongerThanFourKilobytesDecodes() {
        val contents = "x".repeat(9000).toByteArray(Charsets.UTF_8)
        val bytes = java.nio.ByteBuffer.allocate(4 + 8 + 1 + 16 + 4 + 2 + contents.size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(1).putLong(7L).put(2)
            .putFloat(0f).putFloat(0f).putFloat(1f).putFloat(1f)
            .putInt(0)
            .putShort(contents.size.toShort()).put(contents)
            .array()
        assertEquals(9000, SafePdfParser.parseAnnotations(bytes).single().contents.length)
    }

    @Test
    fun annotationsDecodeWithTheirContents() {
        val contents = "note".toByteArray(Charsets.UTF_8)
        val bytes = java.nio.ByteBuffer.allocate(4 + 8 + 1 + 16 + 4 + 2 + contents.size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(1).putLong(42L).put(2)
            .putFloat(1f).putFloat(2f).putFloat(3f).putFloat(4f)
            .putInt(0xFFFF0000.toInt())
            .putShort(contents.size.toShort()).put(contents)
            .array()
        val a = SafePdfParser.parseAnnotations(bytes).single()
        assertEquals(42L, a.id)
        assertEquals(2, a.subtype)
        assertEquals(4f, a.y1)
        assertEquals("note", a.contents)
    }

    /**
     * A listing's record count is read straight off the wire and used to size the result list,
     * so a corrupt or truncated header used to allocate an `Object[count]` before a single
     * record had been validated. At a count near `Int.MAX_VALUE` that is an OutOfMemoryError —
     * an [Error], not an exception, so it escapes the `runCatching` in `SafePdfDocument` that
     * exists to degrade a bad listing to an empty one, and takes the viewer down instead.
     *
     * The buffer physically cannot hold more records than `remaining / minRecordBytes`, so the
     * pre-allocation is capped on that. The loop still runs to the claimed count and stops on
     * the underflow, which is why a plain [RuntimeException] is the expected outcome here.
     */
    @Test
    fun anAbsurdListingCountFailsWithoutExhaustingTheHeap() {
        val headers = mapOf<String, (ByteArray) -> Any>(
            "annotations" to SafePdfParser::parseAnnotations,
            "form fields" to SafePdfParser::parseFormFields,
            "links" to SafePdfParser::parseLinks,
            "outline" to SafePdfParser::parseOutline,
            "search matches" to SafePdfParser::parseSearchMatches,
        )
        // A count of Int.MAX_VALUE over an otherwise empty buffer.
        val bytes = java.nio.ByteBuffer.allocate(4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(Int.MAX_VALUE)
            .array()
        for ((what, parse) in headers) {
            assertFailsWith<RuntimeException>("$what sized its result list from the wire count") {
                parse(bytes)
            }
        }
    }

    /** And a count the buffer CAN justify still decodes every record. */
    @Test
    fun aListingCountTheBufferCanHoldStillDecodesEveryRecord() {
        val buf = java.nio.ByteBuffer.allocate(4 + 3 * (16 + 4 + 2))
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(3)
        repeat(3) { i ->
            buf.putFloat(i.toFloat()).putFloat(0f).putFloat(1f).putFloat(1f)
                .putInt(i).putShort(0)
        }
        val links = SafePdfParser.parseLinks(buf.array())
        assertEquals(3, links.size)
        assertEquals(2, links[2].destPage)
    }

    // ---- the cross-language seam ----

    /**
     * Pins [WireWriter]'s Image arm to the byte count Rust's own serializer produces, so the
     * two transcriptions of the wire format cannot drift apart silently.
     *
     * THE SEAM THIS CLOSES. Every other test in this file decodes bytes from [WireWriter],
     * which is a hand transcription of `wire::serialize`. Rust's `round_trips_all_primitives`
     * reads its output back with a hand-written Reader, which is a second transcription. So
     * both languages verify themselves against their own copy of the format and NOTHING
     * compares Kotlin's decoder against Rust's actual bytes. A field that changed width on
     * one side only would leave all 49 tests here green while every real page desynced — the
     * exact "random shapes appear on the page" failure this decoder exists to prevent.
     *
     * WHY THE IMAGE ARM SPECIFICALLY. It is the one primitive Rust pins to an exact length
     * independently, in `wire::tests::wire_version_matches_the_image_payload_layout`:
     *
     *     let v10_len = (4 + 4 + 4 + 4 + 4) + 1 + 24 + 4 + 4 + 1 + 4 + 1 + 4 + 4;   // = 67
     *     assert_eq!((serialize(&page).len(), WIRE_VERSION), (v10_len, 10), ...)
     *
     * for a 1x1 image with four bytes of data. Asserting the same 67 here couples the two:
     * change the Image layout in `wire.rs` and that test fails; change it in [WireWriter] and
     * this one does. Reproducing the arithmetic term by term rather than writing `67` is
     * deliberate — a bare total would still match if two fields changed by offsetting amounts.
     *
     * VERSION 10, NOT [SafePdfParser.WIRE_VERSION]. Rust EMITS 10; the parser merely
     * understands up to 11. Building at the parser's constant would add the v11 interpolate
     * byte and describe a stream nothing currently produces.
     *
     * RESIDUAL, stated because it is not closed: the other thirteen tags have no Rust-side
     * length constant to pair with, so they remain transcription-against-transcription. A full
     * fix is a golden buffer emitted by Rust and checked into the test resources; this covers
     * the arm that has actually moved twice (v9 alpha, v10 blend) and is next in line to move
     * again (v11 interpolate).
     */
    @Test
    fun theImageArmMatchesTheByteCountRustSerializes() {
        val header = 4 + 4 + 4 + 4 + 4
        val payload = 1 + 24 + 4 + 4 + 1 + 4 + 1 + 4 + 4
        val bytes = WireWriter(version = 10)
            .image(w = 1, h = 1, format = 0, data = ByteArray(4))
            .build()
        assertEquals(
            header + payload,
            bytes.size,
            "WireWriter's Image arm has drifted from wire::serialize — see " +
                "wire::tests::wire_version_matches_the_image_payload_layout, which pins the " +
                "same figure on the Rust side",
        )
        // And it must still decode, so the length agreeing is not a coincidence of two
        // offsetting field-width changes.
        val page = SafePdfParser.parse(bytes)
        assertIs<PdfPrimitive.Image>(page.primitives.single())
    }

    /**
     * The v11 interpolate byte is exactly one byte wider, and nothing else moves. This is the
     * change `wire.rs` documents as next, and the one its comment warns "makes the parser eat
     * the first byte of the image's u32 len as interpolate and desync every primitive from
     * there on" if the two sides land out of step.
     */
    @Test
    fun theV11ImageArmIsExactlyOneByteWiderThanV10() {
        val v10 = WireWriter(version = 10).image(w = 1, h = 1, data = ByteArray(4)).build()
        val v11 = WireWriter(version = 11).image(w = 1, h = 1, data = ByteArray(4)).build()
        assertEquals(1, v11.size - v10.size, "the v11 delta is the single interpolate byte")
        assertIs<PdfPrimitive.Image>(SafePdfParser.parse(v11).primitives.single())
    }

    /**
     * The Kotlin half of `wire::tests::every_arm_has_the_byte_length_the_kotlin_parser_reads`.
     *
     * [theImageArmMatchesTheByteCountRustSerializes] names the residual it leaves: the other
     * thirteen tags had no Rust-side length constant to pair with, so they were
     * transcription-against-transcription — Rust's round-trip test only reads back what Rust
     * wrote, and [WireWriter] is a second hand copy of `wire::serialize`. A field that changed
     * width on ONE side only left both suites green while every real page desynced from that
     * byte on, which is the "random shapes on the page" failure.
     *
     * Rust now pins every arm term by term; these are the same sums against [WireWriter], so a
     * width or ordering change in either serializer fails one of the two tests. Written out
     * field by field, not as totals: a bare total still matches when two fields change by
     * offsetting amounts.
     *
     * VERSION 10, not [SafePdfParser.WIRE_VERSION] — Rust EMITS 10, so the v11 interpolate
     * byte is not in the stream and must not be in the arithmetic.
     */
    @Test
    fun everyArmMatchesTheByteCountRustSerializes() {
        val header = 4 + 4 + 4 + 4 + 4
        fun armLen(write: WireWriter.() -> Unit): Int =
            WireWriter(version = 10).apply(write).build().size - header

        // Text with an N-byte string: tag + x + y + size + argb + len + N + hasStroke +
        // strokeArgb + strokeWidth + renderMode + blend + advance + fontFlags + hScale.
        val textFixed = 1 + 4 + 4 + 4 + 4 + 2 + 1 + 4 + 4 + 1 + 1 + 4 + 1 + 4
        assertEquals(textFixed + 2, armLen { text(text = "ab") }, "Text arm width")

        // Fill: tag + argb + evenOdd + nContours + per contour (nPts + 8 per point) + blend.
        assertEquals(
            1 + 4 + 1 + 2 + (2 + 3 * 8) + 1,
            armLen { fill(contours = listOf(listOf(0f to 0f, 1f to 0f, 1f to 1f))) },
            "Fill arm width",
        )

        // Stroke: tag + argb + width + nDash + 4 per dash + phase + cap + join + miter +
        // nPts + 8 per point + blend.
        assertEquals(
            1 + 4 + 4 + 1 + 2 * 4 + 4 + 1 + 1 + 4 + 2 + 2 * 8 + 1,
            armLen { stroke(dash = floatArrayOf(3f, 2f), pts = listOf(0f to 0f, 1f to 1f)) },
            "Stroke arm width",
        )

        // Image: tag + 6 ctm + w + h + format + alpha + blend + len + payload.
        assertEquals(
            1 + 24 + 4 + 4 + 1 + 4 + 1 + 4 + 4,
            armLen { image(w = 1, h = 1, data = ByteArray(4)) },
            "Image arm width",
        )

        // ImageTiled: tag + 6 ctm + w + h + xstep + ystep + i0 + j0 + nx + ny + alpha +
        // blend + len + payload. No format byte — the cell is always RGBA8888.
        assertEquals(
            1 + 24 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 1 + 4 + 4,
            armLen { imageTiled(w = 1, h = 1, data = ByteArray(4)) },
            "ImageTiled arm width",
        )

        // ClipPush: tag + evenOdd + nPts + 8 per point + nPathOps, then the tagged ops:
        // Move/Line 1 + 8, Cubic 1 + 24, Close 1.
        assertEquals(
            1 + 1 + 2 + 8 + 2,
            armLen { clipPush(pts = listOf(0f to 0f)) },
            "ClipPush arm width (no path ops)",
        )
        assertEquals(
            1 + 1 + 2 + 8 + 2 + (1 + 8) + (1 + 8) + (1 + 24) + 1,
            armLen {
                clipPush(
                    pts = listOf(0f to 0f),
                    pathOps = listOf(
                        PathOp.Move(0f, 0f),
                        PathOp.Line(1f, 1f),
                        PathOp.Cubic(1f, 2f, 3f, 4f, 5f, 6f),
                        PathOp.Close,
                    ),
                )
            },
            "ClipPush path-ops section width",
        )

        // The empty-payload markers are one tag byte each.
        assertEquals(1, armLen { clipPop() }, "ClipPop arm width")
        assertEquals(1, armLen { textClipApply() }, "TextClipApply arm width")
        assertEquals(1, armLen { groupPop() }, "GroupPop arm width")
        assertEquals(1, armLen { softMaskContent() }, "SoftMaskContent arm width")
        assertEquals(1, armLen { softMaskPop() }, "SoftMaskPop arm width")

        assertEquals(1 + 1 + 1 + 4 + 1, armLen { groupPush() }, "GroupPush arm width")
        assertEquals(1 + 1, armLen { softMaskPush() }, "SoftMaskPush arm width")
        assertEquals(
            1 + 256,
            armLen { softMaskTransfer(ByteArray(256)) },
            "SoftMaskTransfer arm width",
        )
    }
}
