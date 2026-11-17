package com.vayunmathur.pdf.ui

import com.vayunmathur.pdf.platform.PdfPrimitive

/** Text writer for [SafePdfDrawContext]: substitute typeface + fill/stroke + text-clip accumulation. */
internal fun SafePdfDrawContext.drawTextPrim(prim: PdfPrimitive.Text) {
    // `outline` means "skip this record entirely" — no paint AND no clip
    // contribution — not merely "already painted". Modes 0-2 set it because
    // Rust already inked the glyph as Fill/Stroke prims from its real contours
    // and there is no clip to build, so only the selection/search payload is
    // left. Modes 4-6 now paint from those same real contours (draw.rs) but
    // deliberately send `outline = false`, because this check sits ABOVE the
    // clip accumulator below and would take the aperture with it; they are made
    // non-painting by `argb = 0` instead. Do not widen this to the clip modes.
    if (prim.outline) return
    if (prim.text.isBlank()) return
    val origin = map(prim.origin)
    val ts = (prim.size * scale).coerceAtLeast(1f)
    val rm = prim.renderMode
    // Tr decides whether the fill is painted (PDF 32000-1 9.3.6). Comparing
    // fill and stroke colour misfires for mode 2 with a single colour — the
    // standard faux-bold — and rendered it hollow. Only suppress the fill when
    // a stroke will actually be painted, since Rust's no-metrics path emits
    // mode 1 with no stroke colour and must still show something.
    //
    // Width ZERO still strokes: §8.4.3.2 makes 0 the thinnest line the device
    // can render, not the absence of one, and draw.rs passes `device_stroke_w`
    // through without a floor (unlike the `.max(0.1)` on Prim::Stroke). Treating
    // it as "no stroke" made `0 w 1 Tr` fall through to the fill branch, which
    // for a stroke-only mode paints prim.color — Rust sets that to the STROKE
    // colour there — so hairline-outlined text came out solid.
    val willStroke = prim.strokeColor != null && prim.strokeWidth >= 0f
    val isStrokeOnly = willStroke && (rm == 1 || rm == 5)

    // v8: substitute the embedded font with a system typeface matching the
    // generic family (sans/serif/mono) + bold/italic recovered by Rust. Rust
    // already emits one glyph per prim at its exact advance-based origin, so
    // letters are correctly spaced without distorting glyph widths — we draw
    // each glyph at its natural width and apply the wire's horizontal scale.
    val tf = pdfTypeface(prim.fontFamily, prim.isBold, prim.isItalic)
    // Only synthesize bold/italic when the real typeface can't supply it,
    // so a genuine bold serif isn't double-weighted into a heavy/wrong look.
    val fakeBold = prim.isBold && !tf.isBold
    val skew = if (prim.isItalic && !tf.isItalic) -0.25f else 0f
    // hScale is NOT Tz alone. `draw.rs`'s `show_string_in` sends
    // `Th * x_scale / y_scale` as its `wire_h_scale`,
    // because `size` carries only the matrix's Y scale, so the X/Y ratio has
    // nowhere else to ride. It is 1.0 for every isotropic matrix, rotations
    // included, and a 4x horizontal stretch (`4 0 0 1 0 0 cm`, ordinary for
    // expanded display text) reaches 4.0 on its own — so the old 0.2..4 range,
    // sized for Tz percentages of 50..200, clamped legitimate anisotropy and
    // drew that text too narrow. Widened to admit real matrices while still
    // bounding what Skia is asked to raster: textScaleX multiplies the glyph's
    // device width, so an unbounded value blows up the glyph cache. Keep this
    // range identical to the one in buildEmbeddedGlyphs, or the selection
    // rectangles stop matching the painted glyphs.
    val hs = prim.hScale.coerceIn(MIN_TEXT_H_SCALE, MAX_TEXT_H_SCALE)
    textPaint.typeface = tf
    textPaint.textSize = ts
    textPaint.isFakeBoldText = fakeBold
    textPaint.textSkewX = skew
    textPaint.textScaleX = hs
    textStrokePaint.typeface = tf
    textStrokePaint.isFakeBoldText = fakeBold
    textStrokePaint.textSkewX = skew
    textStrokePaint.textScaleX = hs
    glyphPathPaint.typeface = tf
    glyphPathPaint.textSize = ts
    glyphPathPaint.textSkewX = skew
    glyphPathPaint.textScaleX = hs
    glyphPathPaint.isFakeBoldText = fakeBold

    // Mode 3 paints nothing (it is how scanned PDFs carry an invisible OCR
    // text layer) and mode 7 is clip-only.
    if (rm != 3 && rm != 7) {
        // FILL FIRST, THEN STROKE. Table 106 names modes 2 and 6 "Fill, then
        // stroke text", and the order is visible whenever the two colours
        // differ: stroking first let the fill paint over the inner half of the
        // stroke, so a black outline round white display text came out at half
        // its weight. Modes 1 and 5 take the stroke branch only.
        if (!isStrokeOnly) {
            textPaint.color = prim.color
            textPaint.textSize = ts
            textPaint.setBlend(prim.blend)
            nativeCanvas.drawText(prim.text, origin.x, origin.y, textPaint)
        }
        if (willStroke) {
            textStrokePaint.color = prim.strokeColor
            textStrokePaint.textSize = ts
            textStrokePaint.strokeWidth = (prim.strokeWidth * scale).coerceAtLeast(0.5f)
            textStrokePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            textStrokePaint.strokeJoin = android.graphics.Paint.Join.ROUND
            textStrokePaint.setBlend(prim.blend)
            nativeCanvas.drawText(prim.text, origin.x, origin.y, textStrokePaint)
        }
    }

    // Accumulate glyph outlines for text-clip render modes (Tr 4-7),
    // applied at the following TextClipApply marker. Use styled glyphPathPaint
    // so clip matches bold/italic visual.
    //
    // KNOWN LIMITATION: the aperture is derived from the SUBSTITUTE face via
    // getTextPath, never from the document's embedded outline — Rust sends a
    // string here, not contours. Modes 4-6 now take their INK from the real
    // embedded contours (draw.rs), so ink and aperture come from different
    // sources and disagree by up to about a third of their union. That is the
    // ceiling, not the typical case: pdfTypeface() matches the generic family
    // and the bold/italic style the wire carries, so the mismatch is a weight
    // or metric difference within the right family, and a correctly matched
    // weight lands below it. A wholly wrong family would be worse but needs
    // Rust's family detection to be wrong, which is a separate defect.
    // Both shapes were substitute-derived before C4, so they were wrong
    // together and this was invisible; it is newly VISIBLE, not newly wrong.
    // Fixing it means carrying the real contours across the wire — see the
    // carrier constraint on ClipPush above.
    //
    // CONTRACT: every Text at rm 4..7 MUST be followed by a TextClipApply.
    // `textClipPath` is reset ONLY in that arm, so a record that never gets a
    // marker leaves its outlines pending and folds them into the NEXT text
    // object's clip. Rust guarantees this by latching on the same condition it
    // emits the record under; a latch predicate that misses an emit path
    // (e.g. the whole-run no-font-metrics record, which bypasses for_each_code)
    // breaks it.
    if (rm in 4..7) {
        tmpGlyphPath.reset()
        glyphPathPaint.getTextPath(prim.text, 0, prim.text.length, origin.x, origin.y, tmpGlyphPath)
        textClipPath.addPath(tmpGlyphPath)
    }
}
