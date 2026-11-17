package com.vayunmathur.pdf.platform

import androidx.compose.ui.geometry.Offset
import com.vayunmathur.library.log.Log
import com.vayunmathur.pdf.platform.SafePdfParser.orIfNonFinite
import java.nio.ByteBuffer

/**
 * Per-tag primitive readers for [SafePdfParser.parse].
 *
 * Extracted so the page loop stays small. Each reader consumes exactly one
 * primitive's payload from [buf] and appends to [out], or throws
 * [IllegalArgumentException] when the payload is truncated — the caller's
 * per-primitive guard converts that into "keep the clean prefix".
 *
 * Version gates mirror the wire: every field newer than the declared version
 * stays unread so the stream stays in sync with what Rust emitted.
 */
internal class PrimitiveDecoder(
    private val wireVersion: Int,
    private val out: MutableList<PdfPrimitive>,
) {
    val isV2OrV3: Boolean = wireVersion >= SafePdfParser.WIRE_VERSION_V2
    val isV4: Boolean = wireVersion >= SafePdfParser.WIRE_VERSION_V4
    val isV5: Boolean = wireVersion >= SafePdfParser.WIRE_VERSION_V5
    val isV6: Boolean = wireVersion >= SafePdfParser.WIRE_VERSION_V6
    val isV7: Boolean = wireVersion >= SafePdfParser.WIRE_VERSION_V7
    val isV8: Boolean = wireVersion >= SafePdfParser.WIRE_VERSION_V8
    val isV9: Boolean = wireVersion >= SafePdfParser.WIRE_VERSION_V9
    val isV10: Boolean = wireVersion >= SafePdfParser.WIRE_VERSION_V10
    val isV11: Boolean = wireVersion >= SafePdfParser.WIRE_VERSION_V11

    var softMaskDepth: Int = 0
    var outermostSoftMaskStart: Int = -1

    /**
     * Reads one primitive (tag byte already consumed by the caller loop via [tag]) and
     * appends it. Returns false when the page should stop (truncation/desync that
     * consumed no further input is possible from here); unknown tags always stop.
     */
    fun readOne(buf: ByteBuffer, tag: Int, count: Int, wireVersion: Int, primIndex: Int): Boolean {
        when (tag) {
            SafePdfParser.TAG_TEXT -> readText(buf)
            SafePdfParser.TAG_FILL -> readFill(buf)
            SafePdfParser.TAG_STROKE -> readStroke(buf)
            SafePdfParser.TAG_IMAGE -> readImage(buf)
            SafePdfParser.TAG_IMAGE_TILED -> readImageTiled(buf)
            SafePdfParser.TAG_CLIP_PUSH,
            SafePdfParser.TAG_CLIP_POP,
            SafePdfParser.TAG_TEXT_CLIP_APPLY,
            SafePdfParser.TAG_GROUP_PUSH,
            SafePdfParser.TAG_GROUP_POP,
            -> readClipOrGroup(buf, tag)
            SafePdfParser.TAG_SMASK_PUSH,
            SafePdfParser.TAG_SMASK_CONTENT,
            SafePdfParser.TAG_SMASK_POP,
            SafePdfParser.TAG_SMASK_TRANSFER,
            -> {
                if (!readSmask(buf, tag)) return false
            }
            else -> {
                // The payload length of an unknown tag is unknowable, so the read
                // position is now inside a payload and every later tag byte would be
                // random data — which decodes into plausible-looking garbage primitives.
                // Kotlin knows all 14 tags Rust emits, so an unknown tag means the stream
                // has desynced: keep what decoded cleanly and stop.
                Log.status(
                    TAG,
                    "unknown tag $tag at offset ${buf.position()} (wire v$wireVersion), " +
                        "prim $primIndex of $count — wire desync, truncating page",
                )
                return false
            }
        }
        return true
    }

    private fun readClipOrGroup(buf: ByteBuffer, tag: Int) {
        when (tag) {
            SafePdfParser.TAG_CLIP_PUSH -> readClipPush(buf)
            SafePdfParser.TAG_CLIP_POP -> out.add(PdfPrimitive.ClipPop)
            SafePdfParser.TAG_TEXT_CLIP_APPLY -> out.add(PdfPrimitive.TextClipApply)
            SafePdfParser.TAG_GROUP_PUSH -> readGroupPush(buf)
            SafePdfParser.TAG_GROUP_POP -> out.add(PdfPrimitive.GroupPop)
            else -> {}
        }
    }

    private fun readSmask(buf: ByteBuffer, tag: Int): Boolean {
        when (tag) {
            SafePdfParser.TAG_SMASK_PUSH -> readSmaskPush(buf)
            SafePdfParser.TAG_SMASK_CONTENT -> out.add(PdfPrimitive.SoftMaskContent)
            SafePdfParser.TAG_SMASK_POP -> readSmaskPop()
            SafePdfParser.TAG_SMASK_TRANSFER -> {
                if (!readSmaskTransfer(buf)) return false
            }
            else -> {}
        }
        return true
    }

    fun readText(buf: ByteBuffer) {
        val x = buf.float
        val y = buf.float
        // Reachable without exotic syntax: an overflowing `Tf` literal times a
        // zero-scale matrix is `inf * 0` = NaN. Zero here becomes one pixel via
        // the renderer's floor, rather than a NaN Paint.textSize.
        val size = buf.float.orIfNonFinite(0f)
        val argb = buf.int
        val len = buf.short.toInt() and U16_MASK
        // No upper-bound rejection: len is u16-bounded and Rust truncates at
        // MAX_TEXT_BYTES (65535), so anything up to that is legitimate. The old
        // 4096 cap threw and destroyed the entire page over one long run.
        requireBytes(buf, len, "Text length truncated")
        val strBytes = ByteArray(len)
        buf.get(strBytes)
        val (strokeColor, strokeWidth) = readTextStroke(buf)
        val renderMode = readVersionedByte(buf, isV4, "Text v4 renderMode truncated")
        val blend = readBlend(buf, isV5, "Text v5 blend truncated")
        val txt = String(strBytes, Charsets.UTF_8)
        val adv = readAdvance(buf, txt, size)
        val font = readFontFlags(buf)
        // Unlike the scalars above there is no sane default for a position, and
        // a NaN origin is not harmless: it paints nothing but still produces a
        // selection rectangle whose distance comparisons are all false, which
        // breaks nearestGlyph for the page. Every byte of this primitive has
        // been consumed, so dropping it leaves the stream in sync, and Text
        // carries no bracket that a later primitive is paired with.
        if (!x.isFinite() || !y.isFinite()) {
            Log.status(TAG, "text primitive has a non-finite origin, dropping it")
            return
        }
        out.add(
            PdfPrimitive.Text(
                origin = Offset(x, y),
                size = size,
                color = argb,
                text = txt,
                strokeColor = strokeColor,
                strokeWidth = strokeWidth,
                advance = adv,
                renderMode = renderMode,
                blend = blend,
                isBold = font.isBold,
                isItalic = font.isItalic,
                fontFamily = font.family,
                outline = font.outline,
                hScale = font.hScale,
            )
        )
    }

    private fun readTextStroke(buf: ByteBuffer): Pair<Int?, Float> {
        if (!isV2OrV3) return null to 0f
        requireBytes(buf, STROKE_TAIL_BYTES, "Text v2 truncated")
        val hasStroke = buf.get().toInt() != 0
        val sArgb = buf.int
        val sWidth = buf.float
        if (hasStroke) return sArgb to sWidth.orIfNonFinite(0f)
        return null to 0f
    }

    private fun readAdvance(buf: ByteBuffer, txt: String, size: Float): Float {
        // v7 carries the true device-space glyph advance; older wires
        // fall back to the size*0.5*len heuristic, and so does a non-finite
        // one. buildEmbeddedGlyphs guards this with `advance > 0f`, which
        // INFINITY passes, and it then scales the whole run's selection
        // geometry to infinity off one overflowed glyph.
        val heuristic = size * ADVANCE_HEURISTIC * txt.length.coerceAtLeast(1)
        if (!isV7) return heuristic
        requireBytes(buf, FLOAT_BYTES, "Text v7 advance truncated")
        return buf.float.orIfNonFinite(heuristic)
    }

    private class FontFlags(
        val isBold: Boolean,
        val isItalic: Boolean,
        val family: Int,
        val outline: Boolean,
        val hScale: Float,
    )

    private fun readFontFlags(buf: ByteBuffer): FontFlags {
        if (!isV8) return FontFlags(false, false, 0, false, 1f)
        requireBytes(buf, FONT_FLAGS_BYTES, "Text v8 fontFlags truncated")
        val fontFlags = buf.get().toInt() and BYTE_MASK
        val isBold = fontFlags and FONT_BOLD_BIT != 0
        val isItalic = fontFlags and FONT_ITALIC_BIT != 0
        // Bits 2-3 carry the generic family: 0 sans, 1 serif, 2 mono.
        val family = (fontFlags shr FONT_FAMILY_SHIFT) and FONT_FAMILY_MASK
        // Bit 4: glyph already drawn as outline fills (don't paint).
        val outline = fontFlags and FONT_OUTLINE_BIT != 0
        // Fall back to the identity rather than trusting the producer to
        // have bounded this. Rust bounds the geometric half (draw.rs
        // `aniso.clamp`) but the Tz half is a bare `v / 100.0` at
        // interpret.rs, and these bytes are the trust boundary regardless.
        val hScale = buf.float.orIfNonFinite(1f)
        return FontFlags(isBold, isItalic, family, outline, hScale)
    }

    fun readFill(buf: ByteBuffer) {
        val argb = buf.int
        val evenOdd = buf.get().toInt() != 0
        val contours = if (isV6) {
            val n = buf.short.toInt() and U16_MASK
            (0 until n).map { SafePdfParser.readPoints(buf) }
        } else {
            // v5 and earlier: a single polygon per fill.
            listOf(SafePdfParser.readPoints(buf))
        }
        val blend = readBlend(buf, isV5, "Fill v5 blend truncated")
        out.add(PdfPrimitive.FillPath(argb, evenOdd, contours, blend))
    }

    fun readStroke(buf: ByteBuffer) {
        val argb = buf.int
        val strokeWidth = buf.float
        // u8 on the wire, so the allocation is bounded at 255 regardless.
        // Rust's `MAX_DASH_LEN` is 32 (graphics_state.rs), not 64 — but
        // `draw::emit_stroke` duplicates an odd-length array, since §8.4.3.6
        // needs an even number of on/off phases, so the count actually EMITTED
        // can be up to twice that. 64 is that doubled worst case, not the
        // constant. Never reject — the old `> 32` throw discarded the whole
        // page over one stroke's dash array, and nothing here depends on the
        // producer's bound anyway.
        val nDash = buf.get().toInt() and BYTE_MASK
        requireBytes(buf, nDash * FLOAT_BYTES + FLOAT_BYTES, "Stroke dash truncated")
        val dash = FloatArray(nDash) { buf.float }
        val dashPhase = buf.float
        val (cap, join, miter) = readCapJoin(buf)
        val points = SafePdfParser.readPoints(buf)
        val blend = readBlend(buf, isV5, "Stroke v5 blend truncated")
        out.add(
            PdfPrimitive.StrokePath(argb, strokeWidth, dash, dashPhase, points, cap, join, miter, blend)
        )
    }

    private fun readCapJoin(buf: ByteBuffer): Triple<Int, Int, Float> {
        if (!isV2OrV3) return Triple(0, 0, DEFAULT_MITER)
        requireBytes(buf, CAP_JOIN_BYTES, "Stroke v2 cap/join truncated")
        val cap = buf.get().toInt() and BYTE_MASK
        val join = buf.get().toInt() and BYTE_MASK
        return Triple(cap, join, buf.float)
    }

    fun readImage(buf: ByteBuffer) {
        val ctm = FloatArray(CTM_SIZE) { buf.float }
        val w = buf.int
        val h = buf.int
        if (SafePdfImages.badDimensions(w, h)) {
            skipBadImage(buf)
            return
        }
        val format = buf.get().toInt()
        val imgAlpha = readImageAlpha(buf)
        val imgBlend = readImageBlend(buf)
        // v11: PDF 32000-1 §8.9.5.1 Table 89 — /Interpolate defaults to false and
        // bilevel art must never be smoothed. Rust decides per image; an older
        // wire has no opinion, so default to smoothing as before.
        val imgInterpolate = readInterpolate(buf)
        val len = buf.int
        if (len < 0) throw IllegalArgumentException("Negative image data length $len")
        if (buf.remaining() < len) throw IllegalArgumentException("Image data truncated")
        if (SafePdfImages.imageTooLarge(len)) {
            // The buffer is intact here — the length field is exactly how many
            // bytes to step over — so this resyncs. Throwing instead would break
            // out of the loop and discard every LATER primitive too, blanking the
            // rest of the page below a single over-sized JPEG or inline image.
            Log.status(
                TAG,
                "image payload $len exceeds ${SafePdfImages.MAX_IMAGE_DATA_BYTES}, dropping this " +
                    "image and continuing the page",
            )
            buf.position(buf.position() + len)
            return
        }
        val data = ByteArray(len)
        buf.get(data)
        val bmp = SafePdfParser.decodeBitmap(w, h, format, data)
        out.add(PdfPrimitive.Image(ctm, bmp, imgAlpha, imgBlend, imgInterpolate))
    }

    private fun skipBadImage(buf: ByteBuffer) {
        // Unusable dimensions: consume this payload EXACTLY so the stream
        // stays in sync, then drop the primitive. If it cannot be skipped
        // cleanly the buffer is untrustworthy, so stop rather than decode
        // garbage from a desynced offset.
        //
        // The PIXEL budget is deliberately NOT tested here. `format` is not
        // read until below, so a test here can only reject every format
        // alike — and Rust drops its own pixel guard for the JPEG
        // passthrough precisely because the platform decoder subsamples it
        // (images.rs:1082-1087). Rejecting here deleted every JPEG over
        // 16 Mpx, which is an ordinary phone photo, with no bitmap, no log
        // and nothing in logcat. The budget now applies per format in
        // `decodeBitmap`, where the memory is actually committed.
        val fixed = FORMAT_BYTE + (if (isV9) FLOAT_BYTES else 0) +
            (if (isV10) BLEND_BYTE else 0) + (if (isV11) INTERPOLATE_BYTE else 0) +
            LENGTH_BYTES
        if (buf.remaining() < fixed) throw IllegalArgumentException("Image dims truncated")
        buf.get() // format
        if (isV9) buf.float // alpha
        if (isV10) buf.get() // blend
        if (isV11) buf.get() // interpolate
        val skipLen = buf.int
        if (skipLen < 0 || buf.remaining() < skipLen) {
            throw IllegalArgumentException("Image skip truncated")
        }
        buf.position(buf.position() + skipLen)
    }

    private fun readImageAlpha(buf: ByteBuffer): Float {
        if (!isV9) return 1f
        requireBytes(buf, FLOAT_BYTES, "Image v9 alpha truncated")
        return buf.float.coerceIn(0f, 1f)
    }

    private fun readImageBlend(buf: ByteBuffer): BlendMode {
        return readBlend(buf, isV10, "Image v10 blend truncated")
    }

    private fun readInterpolate(buf: ByteBuffer): Boolean {
        if (!isV11) return true
        requireBytes(buf, 1, "Image v11 interpolate truncated")
        return buf.get().toInt() != 0
    }

    fun readClipPush(buf: ByteBuffer) {
        val evenOdd = buf.get().toInt() != 0
        val pts = SafePdfParser.readPoints(buf)
        val pathOps = if (isV4) SafePdfParser.readPathOps(buf) else null
        // Always emit, even when the geometry is degenerate. Rust increments its
        // clip depth for such a push and so still sends the matching ClipPop;
        // dropping the push here left that pop unmatched, and it then released an
        // ENCLOSING clip early — content bleeding outside its box. drawSafePage
        // already handles a degenerate push by saving without narrowing the clip.
        out.add(PdfPrimitive.ClipPush(evenOdd, pts, pathOps))
    }

    fun readGroupPush(buf: ByteBuffer) {
        requireBytes(buf, GROUP_PUSH_BYTES, "GroupPush truncated")
        val isolated = buf.get().toInt() != 0
        val knockout = buf.get().toInt() != 0
        val alpha = buf.float.coerceIn(0f, 1f)
        val blendCode = buf.get().toInt() and BYTE_MASK
        out.add(PdfPrimitive.GroupPush(isolated, knockout, alpha, BlendMode.fromCode(blendCode)))
    }

    fun readSmaskPush(buf: ByteBuffer) {
        requireBytes(buf, 1, "SoftMaskPush truncated")
        val maskType = buf.get().toInt() and BYTE_MASK
        if (softMaskDepth == 0) outermostSoftMaskStart = out.size
        softMaskDepth++
        out.add(PdfPrimitive.SoftMaskPush(maskType))
    }

    fun readSmaskPop() {
        out.add(PdfPrimitive.SoftMaskPop)
        if (softMaskDepth > 0) softMaskDepth--
    }

    fun readSmaskTransfer(buf: ByteBuffer): Boolean {
        // Consistent with the robustness contract: a truncated tail means
        // the buffer ended, so keep the prefix that decoded rather than throwing
        // and losing the whole page over the last primitive.
        if (buf.remaining() < SafePdfParser.TRANSFER_LUT_SIZE) {
            Log.status(TAG, "SoftMaskTransfer truncated, truncating page")
            return false
        }
        val lut = ByteArray(SafePdfParser.TRANSFER_LUT_SIZE)
        buf.get(lut)
        out.add(SafePdfParser.fitTransferLut(lut))
        return true
    }

    fun readImageTiled(buf: ByteBuffer) {
        val ctm = FloatArray(CTM_SIZE) { buf.float }
        val w = buf.int
        val h = buf.int
        val xstep = buf.float
        val ystep = buf.float
        val i0 = buf.int
        val j0 = buf.int
        val nxRaw = buf.int
        val nyRaw = buf.int
        requireBytes(buf, TILE_TAIL_BYTES, "ImageTiled truncated")
        val tileAlpha = buf.float.coerceIn(0f, 1f)
        val tileBlend = BlendMode.fromCode(buf.get().toInt() and BYTE_MASK)
        val len = buf.int
        if (len < 0) throw IllegalArgumentException("Negative ImageTiled data length $len")
        if (buf.remaining() < len) throw IllegalArgumentException("ImageTiled data truncated")
        if (SafePdfImages.imageTooLarge(len)) {
            Log.status(
                TAG,
                "tiling cell payload $len exceeds ${SafePdfImages.MAX_IMAGE_DATA_BYTES}, dropping " +
                    "this pattern and continuing the page",
            )
            buf.position(buf.position() + len)
            return
        }
        val data = ByteArray(len)
        buf.get(data)
        // Format 0: the cell is always raw RGBA8888, so there is no format byte.
        val bmp = SafePdfParser.decodeBitmap(w, h, 0, data)
        // The lattice extent only sizes the filled region — a REPEAT shader tiles
        // infinitely — so an absurd count costs nothing to draw, but clamp it so it
        // cannot produce non-finite path coordinates.
        val nx = nxRaw.coerceIn(0, SafePdfParser.MAX_LATTICE_CELLS)
        val ny = nyRaw.coerceIn(0, SafePdfParser.MAX_LATTICE_CELLS)
        out.add(
            PdfPrimitive.ImageTiled(ctm, bmp, xstep, ystep, i0, j0, nx, ny, tileAlpha, tileBlend)
        )
    }

    private fun readVersionedByte(buf: ByteBuffer, gated: Boolean, message: String): Int {
        if (!gated) return 0
        requireBytes(buf, 1, message)
        return buf.get().toInt() and BYTE_MASK
    }

    private fun readBlend(buf: ByteBuffer, gated: Boolean, message: String): BlendMode {
        if (!gated) return BlendMode.Normal
        requireBytes(buf, 1, message)
        return BlendMode.fromCode(buf.get().toInt() and BYTE_MASK)
    }

    private fun requireBytes(buf: ByteBuffer, count: Int, message: String) {
        if (buf.remaining() < count) throw IllegalArgumentException(message)
    }

    companion object {
        private const val TAG = "SafePdfParser"
        private const val U16_MASK = 0xFFFF
        private const val BYTE_MASK = 0xFF
        private const val FLOAT_BYTES = 4
        private const val LENGTH_BYTES = 4
        private const val FORMAT_BYTE = 1
        private const val BLEND_BYTE = 1
        private const val INTERPOLATE_BYTE = 1
        private const val STROKE_TAIL_BYTES = 9
        private const val FONT_FLAGS_BYTES = 5
        private const val FONT_BOLD_BIT = 1
        private const val FONT_ITALIC_BIT = 2
        private const val FONT_FAMILY_SHIFT = 2
        private const val FONT_FAMILY_MASK = 0x3
        private const val FONT_OUTLINE_BIT = 0x10
        private const val ADVANCE_HEURISTIC = 0.5f
        private const val CTM_SIZE = 6
        private const val DEFAULT_MITER = 10f
        private const val CAP_JOIN_BYTES = 6
        private const val GROUP_PUSH_BYTES = 7
        private const val TILE_TAIL_BYTES = 5
    }
}
