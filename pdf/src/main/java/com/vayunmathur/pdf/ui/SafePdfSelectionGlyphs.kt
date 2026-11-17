package com.vayunmathur.pdf.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import com.vayunmathur.library.ocr.OcrEngine
import com.vayunmathur.pdf.platform.PdfPrimitive
import com.vayunmathur.pdf.platform.SafePdfPage

/**
 * A single selectable glyph in reading order - stores String for ligatures fi etc.
 *
 * The corners are on-screen (canvas px) in reading order: [p0] -> [p1] runs along
 * the text and [p0] -> [p3] spans its height, so a glyph lifted off a skewed
 * scan keeps its slant. Embedded text and upright scans give an axis-aligned
 * quad, for which the derived [left]/[top]/[right]/[bottom] are exact.
 */
internal data class SelGlyph(
    val ch: String,
    val p0: Offset,
    val p1: Offset,
    val p2: Offset,
    val p3: Offset,
) {
    val left: Float get() = minOf(p0.x, p1.x, p2.x, p3.x)
    val top: Float get() = minOf(p0.y, p1.y, p2.y, p3.y)
    val right: Float get() = maxOf(p0.x, p1.x, p2.x, p3.x)
    val bottom: Float get() = maxOf(p0.y, p1.y, p2.y, p3.y)
    val center: Offset get() = Offset((p0.x + p2.x) / 2f, (p0.y + p2.y) / 2f)
}

/** An axis-aligned [SelGlyph], for embedded text and upright scans. */
internal fun selGlyph(ch: String, left: Float, top: Float, right: Float, bottom: Float) = SelGlyph(
    ch,
    Offset(left, top),
    Offset(right, top),
    Offset(right, bottom),
    Offset(left, bottom),
)

/**
 * Build the substitute [android.graphics.Typeface] for a text primitive. The
 * embedded PDF font isn't rasterized, so we pick a system typeface matching the
 * generic family (0 sans-serif, 1 serif, 2 monospace) recovered by the Rust core
 * from BaseFont / FontDescriptor, then synthesize bold/italic.
 */
internal fun pdfTypeface(family: Int, bold: Boolean, italic: Boolean): android.graphics.Typeface {
    val base = when (family) {
        1 -> android.graphics.Typeface.SERIF
        2 -> android.graphics.Typeface.MONOSPACE
        else -> android.graphics.Typeface.SANS_SERIF
    }
    val style = when {
        bold && italic -> android.graphics.Typeface.BOLD_ITALIC
        bold -> android.graphics.Typeface.BOLD
        italic -> android.graphics.Typeface.ITALIC
        else -> android.graphics.Typeface.NORMAL
    }
    return android.graphics.Typeface.create(base, style)
}

/** A glyph plus its page-space ordering keys (baseline-Y desc, X asc) for merge+sort. */
internal data class OrderedGlyph(val orderY: Float, val orderX: Float, val glyph: SelGlyph)

/** Pages with fewer than this many embedded glyphs are treated as scanned → OCR. */
internal const val MIN_EMBEDDED_GLYPHS_FOR_TEXT = 6

/** Max distance (page px) a long-press may be from a glyph to start a selection. */
internal const val SELECT_HIT_PX = 80f

/**
 * Bounds on the wire's horizontal text scale, applied to `Paint.textScaleX`.
 *
 * The field is `Th * x_scale / y_scale` (draw.rs), not the Tz percentage its name suggests,
 * so it legitimately reaches the single digits on an anisotropic text matrix. The clamp
 * exists only to bound what Skia is asked to raster — `textScaleX` multiplies the glyph's
 * device width, and an unbounded value blows up the glyph cache — not to express any PDF
 * limit. Both consumers must use the same range: [drawSafePage] paints with it and
 * [buildEmbeddedGlyphs] measures selection rectangles with it, and they have to agree.
 *
 * Rust bounds the geometric factor to the same 0.01..100 before it reaches the wire, so on
 * a well-formed stream this clamp normally never bites. It is deliberately kept as a
 * second line: Rust does NOT bound the Tz half of the product (`interpret.rs` takes
 * `Tz / 100` unchecked), so a pathological `Tz` still arrives large. NaN is handled at the
 * decoder instead — `coerceIn` propagates it — see the hScale read in [SafePdfParser].
 */
internal const val MIN_TEXT_H_SCALE = 0.01f
internal const val MAX_TEXT_H_SCALE = 100f

/**
 * Build ordered selectable glyphs from a page's embedded Text primitives. Uses
 * accurate glyph advances via Text.advance and Paint.measureText for ligatures /
 * multi-char runs, with bold/italic so measured widths match the painted glyphs.
 */
internal fun buildEmbeddedGlyphs(page: SafePdfPage, ch: Float, scale: Float): List<OrderedGlyph> {
    val list = ArrayList<OrderedGlyph>()
    val tmpPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        isSubpixelText = true
        isLinearText = true
    }
    for (prim in page.primitives) {
        if (prim !is PdfPrimitive.Text || prim.text.isEmpty()) continue
        val selTf = pdfTypeface(prim.fontFamily, prim.isBold, prim.isItalic)
        tmpPaint.typeface = selTf
        tmpPaint.isFakeBoldText = prim.isBold && !selTf.isBold
        tmpPaint.textSkewX = if (prim.isItalic && !selTf.isItalic) -0.25f else 0f
        tmpPaint.textScaleX = prim.hScale.coerceIn(MIN_TEXT_H_SCALE, MAX_TEXT_H_SCALE)
        tmpPaint.textSize = prim.size * scale
        val textStr = prim.text
        val measuredTotal = tmpPaint.measureText(textStr)
        // A run that is PAINTED (drawText with this same paint) occupies exactly
        // measuredTotal, so per-glyph measured widths are what put the selection rects on
        // the pixels the user sees. A run that is NOT painted is aligned to whatever is
        // underneath instead, and only `advance` (the true device-space advance, wire v7)
        // knows where that is. Substitute-typeface metrics have no relation to it, so
        // rescale the run to span `advance`. Characters are still spread evenly within the
        // run, as they were before — the fix is the run's total extent, which is where the
        // error accumulated and grew with run length.
        //
        // §9.3.6 Table 106 makes modes 3 AND 7 the two that paint nothing — 3 invisible,
        // 7 clip-only — so both belong here, along with a glyph already drawn from its real
        // outline. Mode 7 was originally missed because it was rare; it stopped being rare
        // when interpret.rs:118 `hidden_render_mode` began routing OC-hidden clipping runs
        // to 7 instead of collapsing them to 3, which is where that text used to pick up
        // this alignment.
        val alignToAdvance = prim.renderMode == 3 || prim.renderMode == 7 || prim.outline
        val advanceFit = if (alignToAdvance && measuredTotal > 0f && prim.advance > 0f) {
            prim.advance * scale / measuredTotal
        } else {
            1f
        }
        val perGlyphMeasured = measuredTotal * advanceFit / textStr.length
        var curPxPage = prim.origin.x
        for (c in textStr) {
            val px = curPxPage
            val singleGlyph = textStr.length == 1
            val cw = if (singleGlyph) prim.advance * scale else perGlyphMeasured
            val stepPage = if (singleGlyph) prim.advance else perGlyphMeasured / scale
            val left = px * scale
            val right = left + cw
            val baseline = ch - prim.origin.y * scale
            val top = baseline - prim.size * scale
            list.add(OrderedGlyph(-prim.origin.y, px, selGlyph(c.toString(), left, top, right, baseline)))
            curPxPage += stepPage
        }
    }
    return list
}

/**
 * Run OCR on the page's largest raster image and synthesize selectable glyphs
 * from the recognized line quads, so scanned PDFs with no embedded text layer
 * become selectable. Characters are distributed evenly along each line
 * (monospace approximation) which is enough for word-level and range selection.
 * Returns empty if OCR is unavailable or the page has no decodable image.
 */
internal suspend fun ocrPageGlyphs(page: SafePdfPage, ch: Float, scale: Float, ocr: OcrEngine): List<OrderedGlyph> {
    if (!ocr.isAvailable()) return emptyList()
    val img = page.primitives.filterIsInstance<PdfPrimitive.Image>()
        .mapNotNull { p -> p.bitmap?.let { p to it } }
        .maxByOrNull { (p, _) -> kotlin.math.abs(p.ctm[0] * p.ctm[3] - p.ctm[1] * p.ctm[2]) }
        ?: return emptyList()
    val (prim, bmp) = img
    val result = ocr.recognizeDetailed(bmp)
    if (result.boxes.isEmpty()) return emptyList()

    val m = prim.ctm
    val bw = bmp.width.toFloat().coerceAtLeast(1f)
    val bh = bmp.height.toFloat().coerceAtLeast(1f)
    // Bitmap px -> canvas px in one matrix, built exactly like the renderer does
    // for this same image, so the text lands wherever the image was actually
    // drawn - including rotated, flipped or sheared image CTMs, which the old
    // per-axis arithmetic laid out backwards.
    fun unitToCanvas(u: Float, v: Float): Offset {
        val pageX = m[0] * u + m[2] * v + m[4]
        val pageY = m[1] * u + m[3] * v + m[5]
        return Offset(pageX * scale, ch - pageY * scale)
    }
    // Image row 0 is the top, so v (unit-square, bottom-up) = 1 - py/bh.
    val c00 = unitToCanvas(0f, 1f)
    val c10 = unitToCanvas(1f, 1f)
    val c11 = unitToCanvas(1f, 0f)
    val c01 = unitToCanvas(0f, 0f)
    val toCanvas = android.graphics.Matrix()
    if (!toCanvas.setPolyToPoly(
            floatArrayOf(0f, 0f, bw, 0f, bw, bh, 0f, bh), 0,
            floatArrayOf(c00.x, c00.y, c10.x, c10.y, c11.x, c11.y, c01.x, c01.y), 0,
            4,
        )
    ) {
        return emptyList()
    }

    val out = ArrayList<OrderedGlyph>()
    val pts = FloatArray(8)
    for (box in result.boxes) {
        val text = box.text
        if (text.isEmpty()) continue
        val n = text.length
        for (i in 0 until 4) {
            pts[i * 2] = box.corners[i].x
            pts[i * 2 + 1] = box.corners[i].y
        }
        toCanvas.mapPoints(pts)
        val q0 = Offset(pts[0], pts[1])
        val q1 = Offset(pts[2], pts[3])
        val q2 = Offset(pts[4], pts[5])
        val q3 = Offset(pts[6], pts[7])
        val runLen = kotlin.math.hypot(q1.x - q0.x, q1.y - q0.y)
        if (runLen <= 0f || kotlin.math.hypot(q3.x - q0.x, q3.y - q0.y) <= 0f) continue
        val ux = (q1.x - q0.x) / runLen
        val uy = (q1.y - q0.y) / runLen
        // The ordering keys have to stay in page space to interleave with the
        // embedded glyphs' keys. Banding on the quad centre keeps a slanted
        // line's characters in one line, and projecting onto the line's own
        // advance direction keeps them in reading order even when that direction
        // runs right-to-left on screen (an upside-down or X-flipped image CTM).
        // For upright text the projection is just the centre's x.
        val orderY = ((q0.y + q2.y) / 2f - ch) / scale
        for (k in 0 until n) {
            val t0 = k.toFloat() / n
            val t1 = (k + 1).toFloat() / n
            val glyph = SelGlyph(
                text[k].toString(),
                lerp(q0, q1, t0),
                lerp(q0, q1, t1),
                lerp(q3, q2, t1),
                lerp(q3, q2, t0),
            )
            val c = glyph.center
            out.add(OrderedGlyph(orderY, (c.x * ux + c.y * uy) / scale, glyph))
        }
    }
    return out
}

/** Nearest glyph index to [p], or null if none within [maxDist] page px. */
internal fun nearestGlyph(g: List<SelGlyph>, p: Offset, maxDist: Float = Float.MAX_VALUE): Int? {
    var best = -1
    var bestD = Float.MAX_VALUE
    for (i in g.indices) {
        val c = g[i].center
        val dd = (c.x - p.x) * (c.x - p.x) + (c.y - p.y) * (c.y - p.y)
        if (dd < bestD) { bestD = dd; best = i }
    }
    return if (best >= 0 && bestD <= maxDist * maxDist) best else null
}

/**
 * True when [a] and [b] belong to the same word (same line, no gap between them).
 *
 * Measured along the line's own advance direction rather than along the screen
 * axes, so a skewed scan's words don't split on the vertical drift between
 * neighbouring characters. For upright text the projections collapse to the
 * plain "same row, small horizontal gap" test this replaced.
 */
internal fun sameWord(a: SelGlyph, b: SelGlyph): Boolean {
    val advance = a.p1 - a.p0
    val len = kotlin.math.hypot(advance.x, advance.y)
    if (len < 1e-3f) return false
    val ux = advance.x / len
    val uy = advance.y / len
    val h = maxOf(
        kotlin.math.hypot(a.p3.x - a.p0.x, a.p3.y - a.p0.y),
        kotlin.math.hypot(b.p3.x - b.p0.x, b.p3.y - b.p0.y),
        1f,
    )
    // offset across the line, between baseline corners: glyphs sharing a baseline
    // line up there whatever their size, so a font-size jump mid-word doesn't
    // read as a row change
    val across = (b.p3.x - a.p3.x) * -uy + (b.p3.y - a.p3.y) * ux
    if (kotlin.math.abs(across) > 0.6f * h) return false
    // gap along the line, from the end of `a` to the start of `b`
    val gap = (b.p0.x - a.p1.x) * ux + (b.p0.y - a.p1.y) * uy
    return gap <= 0.4f * h
}

/** Expand the glyph index [i] to the whole word it belongs to (reading order). */
internal fun wordRangeAt(g: List<SelGlyph>, i: Int): IntRange {
    if (i !in g.indices) return i..i
    if (g[i].ch.isBlank()) return i..i
    var lo = i
    var hi = i
    while (lo - 1 in g.indices && g[lo - 1].ch.isNotBlank() && sameWord(g[lo - 1], g[lo])) lo--
    while (hi + 1 in g.indices && g[hi + 1].ch.isNotBlank() && sameWord(g[hi], g[hi + 1])) hi++
    return lo..hi
}

/** Which selection handle (0 = start, 1 = end) is within grab range of [p], else null. */
internal fun handleAt(g: List<SelGlyph>, p: Offset, r: IntRange): Int? {
    if (r.first !in g.indices || r.last !in g.indices) return null
    // The handles sit on the selection's own baseline corners, so they stay glued
    // to the glyphs on a skewed page instead of floating off to a bounding box.
    val s = g[r.first].p3
    val e = g[r.last].p2
    val dStart = kotlin.math.hypot(s.x - p.x, s.y - p.y)
    val dEnd = kotlin.math.hypot(e.x - p.x, e.y - p.y)
    val grab = 48f
    return when {
        dStart <= grab && dStart <= dEnd -> 0
        dEnd <= grab -> 1
        else -> null
    }
}

/** The selected substring for range [r] over [g] (empty if out of bounds). */
internal fun selectionText(g: List<SelGlyph>, r: IntRange): String {
    if (g.isEmpty() || r.first !in g.indices || r.last !in g.indices || r.last < r.first) return ""
    return g.subList(r.first, r.last + 1).joinToString("") { it.ch }
}
