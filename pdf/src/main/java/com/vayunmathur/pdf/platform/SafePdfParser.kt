package com.vayunmathur.pdf.platform

import com.vayunmathur.pdf.util.PdfNative

import androidx.compose.ui.geometry.Offset
import com.vayunmathur.library.log.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the compact little-endian primitive buffer produced by the native
 * renderer ([PdfNative.renderPage]) into a [SafePdfPage].
 *
 * Wire format v11 (must stay in sync with `pdf/src/main/rust/src/wire.rs`):
 * ```
 * header: u32 MAGIC=0x50444657, u32 VERSION (11 is the newest understood; Rust emits 10),
 *         f32 pageWidth, f32 pageHeight, u32 primitiveCount
 *  Legacy v1 fallback: header is f32 W,H,u32 count (no magic)
 *  v2..v10 fallbacks: same layout with fewer trailing fields for older cached pages
 * per primitive: u8 tag, then payload
 *   1 Text:   f32 x, f32 y, f32 size, u32 argb, u16 len, [utf8 bytes], u8 hasStroke,
 *      u32 strokeArgb, f32 strokeWidth, u8 renderMode (v4), u8 blend (v5), f32 advance (v7),
 *      u8 fontFlags (v8 bold italic), f32 hScale (v8)
 *   2 Fill:   u32 argb, u8 evenOdd, u16 nContours, [u16 nPts, [f32 x,y]...]... (v6), u8 blend (v5)
 *   3 Stroke: u32 argb, f32 width, u8 nDash, [f32 dash]..., f32 phase, u8 cap, u8 join,
 *      f32 miter, u16 nPts, [f32 x, y]..., u8 blend (v5)
 *   4 Image:  6*f32 ctm, u32 w, u32 h, u8 format, f32 alpha (v9), u8 blend (v10),
 *      u8 interpolate (v11), u32 len, [bytes]
 *   5 ClipPush: u8 evenOdd, u16 nPts, [f32 x,y]..., u16 nPathOps, [...] (v4)
 *   6 ClipPop: empty
 *   7 GroupPush: u8 isolated, u8 knockout, f32 alpha, u8 blend
 *   8 GroupPop: empty
 *   9 TextClipApply: empty (v4)
 *   10 SoftMaskPush: u8 maskType (0 alpha, 1 lum) (v5)
 *   11 SoftMaskContent: empty (v5)
 *   12 SoftMaskPop: empty (v5)
 *   13 SoftMaskTransfer: 256 * u8 LUT over the mask value (v11) — the /TR of the immediately
 *      preceding SoftMaskPush. Handled regardless of the declared wire version, so Rust may
 *      start emitting it without a version bump and an older stream that never contains it is
 *      unaffected.
 *   14 ImageTiled: 6*f32 ctm, u32 w, u32 h, f32 xstep, f32 ystep, i32 i0, i32 j0, u32 nx,
 *      u32 ny, f32 alpha, u8 blend, u32 len, [RGBA8888 bytes] — one repeating cell for a
 *      tiling pattern. No format byte; the payload is always raw RGBA8888. Tag-gated, so an
 *      absent emitter is a no-op.
 * ```
 * Pure function -> unit-testable. v9 adds per-image alpha, v10 per-image blend mode, v11 the
 * per-image /Interpolate flag. Rust still declares v10 until it writes that byte, at which
 * point the two must be bumped together — see the note on WIRE_VERSION in wire.rs.
 *
 * Robustness contract: a single malformed or over-sized primitive must never discard the
 * page. [parse] returns whatever decoded cleanly, because the caller
 * ([SafePdfDocument.renderPage]) can only turn a thrown exception into a null page, which
 * the UI shows as an indefinite loading spinner.
 */
object SafePdfParser {

    private const val TAG = "SafePdfParser"

    internal const val TAG_TEXT = 1
    internal const val TAG_FILL = 2
    internal const val TAG_STROKE = 3
    internal const val TAG_IMAGE = 4
    internal const val TAG_CLIP_PUSH = 5
    internal const val TAG_CLIP_POP = 6
    internal const val TAG_GROUP_PUSH = 7
    internal const val TAG_GROUP_POP = 8
    internal const val TAG_TEXT_CLIP_APPLY = 9
    internal const val TAG_SMASK_PUSH = 10
    internal const val TAG_SMASK_CONTENT = 11
    internal const val TAG_SMASK_POP = 12
    internal const val TAG_SMASK_TRANSFER = 13
    internal const val TAG_IMAGE_TILED = 14

    internal const val PATHOP_MOVE = 0
    internal const val PATHOP_LINE = 1
    internal const val PATHOP_CUBIC = 2
    internal const val PATHOP_CLOSE = 3

    const val WIRE_MAGIC: Int = 0x50444657 // 'PDFW' little-endian as u32
    /**
     * Highest wire version this parser understands — NOT the version the producer emits.
     * `WIRE_VERSION` in `pdf/src/main/rust/src/wire.rs` is the emitted one and currently
     * declares 10; it may lag this constant, because every field newer than what it
     * declares stays gated off below, but it must never exceed it. A higher version is not
     * rejected here — [parse] warns and carries on with those gates closed, so it reads too
     * few bytes per primitive and desyncs the rest of the buffer.
     *
     * Rust's `wire::tests::wire_version_is_not_ahead_of_the_kotlin_parser` reads these two
     * declarations straight out of this file to assert that, so keep each of them on one
     * line in exactly the form `const val WIRE_VERSION: Int = <n>` / `= 0x<hex>`.
     */
    const val WIRE_VERSION: Int = 11
    internal const val WIRE_VERSION_V2 = 2
    internal const val WIRE_VERSION_V4 = 4
    internal const val WIRE_VERSION_V5 = 5
    internal const val WIRE_VERSION_V6 = 6
    internal const val WIRE_VERSION_V7 = 7
    internal const val WIRE_VERSION_V8 = 8
    internal const val WIRE_VERSION_V9 = 9
    internal const val WIRE_VERSION_V10 = 10
    internal const val WIRE_VERSION_V11 = 11
    /**
     * Upper bound on primitives decoded from one page — a BACKSTOP against a corrupt count
     * field, not a truncation policy. Over the bound we clamp and render a partial page,
     * never throw, because a throw here discards the whole page.
     *
     * It must sit comfortably ABOVE Rust's own ceiling, which is what actually bounds the
     * stream. Rust enforces `MAX_PRIMITIVES = 300000` (graphics_state.rs:166) at every
     * content-emitting push — guards at ~57 sites across interpret.rs, draw.rs and
     * annotations.rs, plus a hard `prims.truncate` in draw.rs.
     *
     * DO NOT LOWER THIS TO MATCH 300000. The guards protect content-emitting pushes, but the
     * bracket BOOKKEEPING around them is emitted for structural correctness whether or not the
     * cap has been reached — dropping a `ClipPop` would leave its clip un-restored and blank
     * the rest of the page, so several of those pushes and pops are deliberately unguarded
     * (e.g. interpret.rs:247, :1145-1148, :1555). The emitted count can therefore exceed
     * 300000, and a clamp set to exactly 300000 would cut exactly that bookkeeping.
     *
     * The excess is NOT limited to closers. `exceeding_the_primitive_cap_keeps_the_bracket_
     * structure_intact` measures three shapes: overshoot 1 at one fill per tile, 2 at forty
     * fills per tile, and 6 for a form XObject crossing the cap mid-operator, where the excess
     * is ["ClipPop", "ClipPop", "ClipPush", "ClipPop", "ClipPop", "ClipPop"] — a `Do` still
     * pushes its mandatory `/BBox` clip (§8.10.2) and pops it again even though it emits no
     * content. So it is bracket bookkeeping, pushes included, not bracket closers.
     *
     * No tight upper bound on the overshoot has been established, and two attempts to derive
     * one from bracket depth were wrong. Do not replace this constant with a formula: it is a
     * backstop against a corrupt count field, so the correct posture is generous headroom
     * (~700k here) rather than a bound we would have to keep re-proving.
     *
     * NOTE: this clamp is not the tightest bound anywhere. `MAX_CONTENT_OPS`
     * (graphics_state.rs:250, applied at interpret.rs:274 as `ops.iter().take(..)`) was raised
     * from 200000 to 1000000 once it was established that `ops` is a fully-materialised Vec, so
     * `take` frees no memory and the constant was a time guard being used as a memory guard.
     * Rust's `MAX_PRIMITIVES = 300000` is now the binding producer bound. Covered executably by
     * `golden_tests::content_beyond_the_caps_truncates_to_a_valid_page`, which derives its
     * content size from whichever cap is smaller rather than hard-coding one.
     */
    const val MAX_PRIMITIVES = 1_000_000

    /** A /TR transfer function is transmitted as this many u8 samples over the mask value. */
    internal const val TRANSFER_LUT_SIZE = 256
    // Image budgets and decoding live in SafePdfImages with the decoder that enforces them.
    /**
     * Cap on a tiling pattern's lattice extent per axis. The extent only decides how large a
     * region the REPEAT shader is asked to cover, so a big count is not itself expensive, but
     * an absurd one would push the region path's coordinates past what Skia can represent.
     */
    internal const val MAX_LATTICE_CELLS = 100_000
    /**
     * Largest deviation from a straight line, in 8-bit mask levels, still treated as affine.
     * Three levels is below the visible threshold for a mask edge.
     */
    private const val TRANSFER_FIT_TOLERANCE = 3f / 255f

    /**
     * Reduce a /TR LUT to `gain * m + bias` by least squares, marking the result non-affine
     * when the fit is worse than [TRANSFER_FIT_TOLERANCE].
     *
     * PDF 32000-1 §11.6.5.2 requires the mask value to pass through /TR, and an inverting /TR
     * is the standard idiom for "mask out where the group is bright" — so ignoring it hides
     * exactly the wrong half. A soft mask is drawn into a `Canvas.saveLayer`, and the only
     * per-pixel transform available on that composite is a `ColorMatrixColorFilter`, which is
     * affine. Inverting and gain/bias curves fit exactly and so are applied exactly. A
     * genuinely non-linear /TR (gamma, threshold, sampled type-0) cannot be: Android has no
     * LUT colour filter, a saveLayer has no readable pixel buffer to run a table over, and
     * `RuntimeShader`/AGSL cannot act as the filter on a saveLayer composite. Running the
     * table per pixel per frame is not viable either, since drawSafePage re-runs on every
     * frame at every zoom level. Those are reported as non-affine and left untransformed
     * rather than approximated, because a wrong curve hides the wrong half of the group —
     * the exact fault this exists to fix. KNOWN LIMITATION.
     */
    internal fun fitTransferLut(lut: ByteArray): PdfPrimitive.SoftMaskTransfer {
        val n = lut.size
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val x = i / (n - 1).toDouble()
            val y = (lut[i].toInt() and 0xFF) / 255.0
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        val denom = n * sxx - sx * sx
        // Degenerate only if every sample x is identical, which cannot happen for n > 1.
        val gain = if (denom != 0.0) (n * sxy - sx * sy) / denom else 0.0
        val bias = (sy - gain * sx) / n
        var maxErr = 0.0
        for (i in 0 until n) {
            val x = i / (n - 1).toDouble()
            val y = (lut[i].toInt() and 0xFF) / 255.0
            val e = kotlin.math.abs(gain * x + bias - y)
            if (e > maxErr) maxErr = e
        }
        val affine = maxErr <= TRANSFER_FIT_TOLERANCE
        if (!affine) {
            Log.status(TAG, "soft-mask /TR is not affine (max err $maxErr), leaving mask untransformed")
        }
        return PdfPrimitive.SoftMaskTransfer(gain.toFloat(), bias.toFloat(), affine)
    }

    /**
     * This value, or [fallback] when the wire carried a non-finite one.
     *
     * Every float here arrives as four arbitrary bytes across JNI, so NaN and infinity are
     * always representable no matter what the producer guarantees — a desynced buffer alone
     * produces NaN from any bit pattern in the quiet range. They are worth singling out
     * because the usual Kotlin range guards do NOT stop them: `coerceIn` and `coerceAtLeast`
     * are written as `if (this < min) ... else this`, and every comparison against NaN is
     * false, so NaN passes through both untouched and lands in `Paint.textSize`,
     * `Paint.textScaleX` or a selection rectangle. Infinity survives a `> 0f` guard for the
     * same reason and then poisons the running geometry of a whole text run.
     *
     * `draw.rs` also bounds these at the producer, which is the right place for the
     * geometry; this is the decoder refusing to depend on that promise.
     */
    internal fun Float.orIfNonFinite(fallback: Float): Float = if (isFinite()) this else fallback

    fun parse(bytes: ByteArray): SafePdfPage {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < HEADER_MIN_BYTES) throw IllegalArgumentException("Buffer too small")

        val header = readHeader(buf)

        // Safety guards: count caps, version enforcement, dimension sanity.
        //
        // CLAMP, DO NOT THROW. A throw here happens before the per-primitive guard below, so
        // renderPage turns the whole page into null and the UI shows an indefinite spinner —
        // and the Rust producer carries no matching 20000pt bound, so it can legitimately emit
        // a page this side used to reject. Large-format CAD and poster PDFs land here and every
        // real viewer renders them. An over-large page drawn at a clamped size beats a page that
        // never appears.
        //
        // NaN is handled by the same expression: every comparison against it is false, so it
        // takes the fallback rather than reaching SafePdfPageCanvas, whose
        // `Modifier.aspectRatio(width / height)` requires a ratio > 0 and throws on NaN —
        // taking down the composition rather than the one page.
        val (width, height) = clampDimensions(header.rawWidth, header.rawHeight)
        val count = clampCount(header.rawCount)
        warnWireVersion(header.wireVersion)

        val primitives = ArrayList<PdfPrimitive>(count.coerceAtMost(PRIMITIVE_LIST_HINT))
        // Depth of the soft-mask bracket currently open, and the index at which the
        // outermost still-unterminated one began, so a page cut short can be trimmed back
        // to a well-formed boundary instead of left with a half-applied mask.
        val decoder = PrimitiveDecoder(header.wireVersion, primitives)
        var primIndex = 0
        while (primIndex < count && buf.hasRemaining()) {
            // The robustness contract above: a primitive cut short must keep the clean prefix,
            // never discard the page, because renderPage can only turn a throw into a null page
            // and the UI shows that as an indefinite spinner. Each field below throws
            // IllegalArgumentException on truncation, and any unguarded relative get raises
            // BufferUnderflowException — both converted here into the same stop.
            val primStart = buf.position()
            if (!readPrimitiveGuarded(buf, decoder, count, header, primIndex, primStart)) break
            primIndex++
        }

        trimDanglingSoftMask(primitives, decoder)
        return SafePdfPage(width, height, primitives)
    }

    private class PageHeader(
        val wireVersion: Int,
        val rawWidth: Float,
        val rawHeight: Float,
        val rawCount: Int,
    )

    private fun readPrimitiveGuarded(
        buf: ByteBuffer,
        decoder: PrimitiveDecoder,
        count: Int,
        header: PageHeader,
        primIndex: Int,
        primStart: Int,
    ): Boolean {
        return try {
            val tag = buf.get().toInt() and BYTE_MASK
            decoder.readOne(buf, tag, count, header.wireVersion, primIndex)
        } catch (expected: IllegalArgumentException) {
            logPrimitiveFailure(count, header, primIndex, primStart, expected)
            false
        } catch (expected: java.nio.BufferUnderflowException) {
            logPrimitiveFailure(count, header, primIndex, primStart, expected)
            false
        }
    }

    private fun logPrimitiveFailure(
        count: Int,
        header: PageHeader,
        primIndex: Int,
        primStart: Int,
        expected: RuntimeException,
    ) {
        Log.status(
            TAG,
            "primitive $primIndex of $count failed to decode at offset $primStart " +
                "(wire v${header.wireVersion}), keeping clean prefix",
            expected,
        )
    }

    private fun readHeader(buf: ByteBuffer): PageHeader {
        val firstInt = buf.int
        if (firstInt == WIRE_MAGIC) {
            if (buf.remaining() < FULL_HEADER_TAIL_BYTES) {
                throw IllegalArgumentException("v2/v3 header truncated")
            }
            return PageHeader(buf.int, buf.float, buf.float, buf.int)
        }
        // v1 legacy: firstInt was actually width bits, reinterpret
        return PageHeader(
            wireVersion = 1,
            rawWidth = java.lang.Float.intBitsToFloat(firstInt),
            rawHeight = buf.float,
            rawCount = buf.int,
        )
    }

    private fun clampDimensions(rawWidth: Float, rawHeight: Float): Pair<Float, Float> {
        val width = if (rawWidth.isFinite() && rawWidth > 0f) {
            rawWidth.coerceAtMost(MAX_PAGE_DIM)
        } else {
            DEFAULT_PAGE_WIDTH
        }
        val height = if (rawHeight.isFinite() && rawHeight > 0f) {
            rawHeight.coerceAtMost(MAX_PAGE_DIM)
        } else {
            DEFAULT_PAGE_HEIGHT
        }
        if (width != rawWidth || height != rawHeight) {
            Log.status(
                TAG,
                "page dimensions $rawWidth x $rawHeight out of range, clamped to $width x $height"
            )
        }
        return width to height
    }

    private fun clampCount(rawCount: Int): Int {
        val countRaw = rawCount.coerceAtLeast(0)
        val count = countRaw.coerceAtMost(MAX_PRIMITIVES)
        if (count < countRaw) {
            Log.status(
                TAG,
                "primitive count $countRaw exceeds $MAX_PRIMITIVES, rendering the first $count"
            )
        }
        return count
    }

    private fun warnWireVersion(wireVersion: Int) {
        // Accept v1 (legacy), v2, v3 and v4. Newer versions are tolerated via
        // forward-compat parsing as long as the tags are known.
        if (wireVersion in 1..WIRE_VERSION) return
        // Neither direction throws: a throw here becomes a null page and an indefinite
        // spinner. An older-than-known version degrades the same way a newer one does —
        // parse what the tags allow and drop what they do not.
        Log.status(
            "SafePdfParser",
            "Wire version $wireVersion outside 1..$WIRE_VERSION, attempting best-effort parse",
        )
    }

    private fun trimDanglingSoftMask(primitives: MutableList<PdfPrimitive>, decoder: PrimitiveDecoder) {
        // A page cut short — by the count clamp, a desync, or a truncated buffer — can end
        // inside a soft-mask bracket, which renders actively WRONG rather than merely
        // partial: with no mask ever composited the masked content draws fully opaque
        // (a vignette becomes a hard block), and a half-drawn mask erases a ragged region.
        // Trim back to before the bracket, unless that would cost most of the page.
        if (decoder.softMaskDepth <= 0 || decoder.outermostSoftMaskStart < 0) return
        val start = decoder.outermostSoftMaskStart
        val dropped = primitives.size - start
        if (start >= primitives.size / 2) {
            Log.status(TAG, "page ended inside a soft-mask bracket, dropping $dropped trailing prims")
            primitives.subList(start, primitives.size).clear()
        } else {
            Log.status(
                TAG,
                "page ended inside a soft-mask bracket spanning most of the page, keeping it"
            )
        }
    }

    private const val BYTE_MASK = 0xFF
    private const val U16_MASK = 0xFFFF
    private const val POINT_BYTES = 8
    private const val CUBIC_BYTES = 24
    private const val HEADER_MIN_BYTES = 12
    private const val FULL_HEADER_TAIL_BYTES = 16
    private const val MAX_PAGE_DIM = 20000f
    private const val DEFAULT_PAGE_WIDTH = 612f
    private const val DEFAULT_PAGE_HEIGHT = 792f
    private const val PRIMITIVE_LIST_HINT = 4096

    /** Decode the annotation listing buffer from `listAnnotations`. Implemented in [SafePdfListings]. */
    fun parseAnnotations(bytes: ByteArray): List<SafeAnnotation> =
        SafePdfListings.parseAnnotations(bytes)

    /** Decode the form-field listing buffer from `listFormFields`. Implemented in [SafePdfListings]. */
    fun parseFormFields(bytes: ByteArray): List<SafeFormField> =
        SafePdfListings.parseFormFields(bytes)

    /** Decode the search-match buffer from `searchDocument`. Implemented in [SafePdfListings]. */
    fun parseSearchMatches(bytes: ByteArray): List<SafeSearchMatch> =
        SafePdfListings.parseSearchMatches(bytes)

    /** Decode the link listing buffer from `listLinks`. Implemented in [SafePdfListings]. */
    fun parseLinks(bytes: ByteArray): List<SafeLink> =
        SafePdfListings.parseLinks(bytes)

    // Listing-buffer capacity guards, record sizes and the u16-string reader live in
    // [SafePdfListings] with the parsers that use them.

    /** Decode the outline buffer from `listOutline`. Implemented in [SafePdfListings]. */
    fun parseOutline(bytes: ByteArray): List<SafeOutlineItem> =
        SafePdfListings.parseOutline(bytes)

    internal fun readPoints(buf: ByteBuffer): List<Offset> {
        val n = buf.short.toInt() and U16_MASK
        val points = ArrayList<Offset>(n.coerceAtLeast(0))
        repeat(n) {
            if (buf.remaining() < POINT_BYTES) return@repeat
            val x = buf.float
            val y = buf.float
            points.add(Offset(x, y))
        }
        return points
    }

    /** Decode the v4 bezier-retentive clip path-ops section. */
    internal fun readPathOps(buf: ByteBuffer): List<PathOp> {
        val n = buf.short.toInt() and U16_MASK
        val ops = ArrayList<PathOp>(n.coerceAtLeast(0))
        repeat(n) {
            if (!buf.hasRemaining()) return@repeat
            readOnePathOp(buf, ops)
        }
        return ops
    }

    private fun readOnePathOp(buf: ByteBuffer, ops: MutableList<PathOp>) {
        when (buf.get().toInt() and BYTE_MASK) {
            PATHOP_MOVE -> {
                if (buf.remaining() < POINT_BYTES) return
                ops.add(PathOp.Move(buf.float, buf.float))
            }
            PATHOP_LINE -> {
                if (buf.remaining() < POINT_BYTES) return
                ops.add(PathOp.Line(buf.float, buf.float))
            }
            PATHOP_CUBIC -> {
                if (buf.remaining() < CUBIC_BYTES) return
                ops.add(PathOp.Cubic(buf.float, buf.float, buf.float, buf.float, buf.float, buf.float))
            }
            PATHOP_CLOSE -> ops.add(PathOp.Close)
            else -> {}
        }
    }

    // Raster decoding lives in SafePdfImages; these delegate so parse() is unchanged.
    internal fun sampleSizeFor(w: Int, h: Int): Int = SafePdfImages.sampleSizeFor(w, h)

    internal fun decodeBitmap(w: Int, h: Int, format: Int, data: ByteArray): android.graphics.Bitmap? =
        SafePdfImages.decodeBitmap(w, h, format, data)

    internal fun decodeBitmap(w: Int, h: Int, format: Int, data: ByteBuffer): android.graphics.Bitmap? =
        SafePdfImages.decodeBitmap(w, h, format, data)
}
