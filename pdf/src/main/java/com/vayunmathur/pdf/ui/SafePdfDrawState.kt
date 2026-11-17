package com.vayunmathur.pdf.ui

import com.vayunmathur.library.log.Log
import com.vayunmathur.pdf.platform.BlendMode
import com.vayunmathur.pdf.platform.PdfPrimitive

/** Clip / transparency-group / soft-mask writers for [SafePdfDrawContext]. */
internal fun SafePdfDrawContext.applyClipPush(prim: PdfPrimitive.ClipPush) {
    // Save and apply clip; prefer the bezier-retentive path (v4) for
    // accurate curved clips, falling back to the flattened polyline.
    //
    // An empty path here means DO NOT NARROW — the opposite of TextClipApply
    // below, which narrows to nothing. Deliberate: Rust drops an empty ClipPush
    // entirely (interpret.rs), so one arriving here is a degenerate `W n` whose
    // ClipPop would otherwise release an enclosing clip early, whereas an empty
    // text clip is a real §9.4.3 intersection against no outlines. Both contracts
    // are pinned by tests. Do not "harmonize" them, and do not route glyph
    // outlines through ClipPush — it would silently invert the text-clip case.
    nativeCanvas.save()
    saveCount++
    val ops = prim.pathOps
    if (ops != null && ops.isNotEmpty()) {
        val cp = pathOpsToPath(ops)
        cp.fillType = if (prim.evenOdd) android.graphics.Path.FillType.EVEN_ODD else android.graphics.Path.FillType.WINDING
        nativeCanvas.clipPath(cp)
    } else if (prim.points.size >= 3) {
        clipPath.reset()
        val p0 = map(prim.points[0])
        clipPath.moveTo(p0.x, p0.y)
        for (i in 1 until prim.points.size) {
            val p = map(prim.points[i])
            clipPath.lineTo(p.x, p.y)
        }
        clipPath.close()
        clipPath.fillType = if (prim.evenOdd) android.graphics.Path.FillType.EVEN_ODD else android.graphics.Path.FillType.WINDING
        nativeCanvas.clipPath(clipPath)
    }
}

internal fun SafePdfDrawContext.applyClipPop() {
    if (saveCount > 0) {
        nativeCanvas.restore()
        saveCount--
    }
}

internal fun SafePdfDrawContext.applyTextClip() {
    // §9.4.3: at ET the accumulated glyph outlines are combined with the current
    // clip BY INTERSECTION. An empty accumulation intersects to EMPTY, not to
    // absent, so clip unconditionally — a Path with no contours has an empty
    // region and yields exactly that. Skipping the clip instead would let the
    // content that should show only inside the letterforms paint over the whole
    // page. Paired with a later ClipPop (Rust incremented the clip depth).
    nativeCanvas.save()
    saveCount++
    nativeCanvas.clipPath(textClipPath)
    textClipPath.reset()
}

internal fun SafePdfDrawContext.applyGroupPush(prim: PdfPrimitive.GroupPush) {
    // Transparency group with alpha + blend, composited via an offscreen layer.
    //
    // KNOWN LIMITATION: neither /I (isolated) nor /K (knockout) is honoured.
    // Canvas.saveLayer always yields an isolated, non-knockout layer. A
    // non-isolated group would have to see the existing backdrop inside the
    // layer so its blend mode composites against it (PDF 32000-1 11.4.5), and a
    // knockout group needs each element composited against the group's initial
    // backdrop rather than the running result (11.4.6). Neither is expressible
    // with Canvas layers, so both are approximated as isolated non-knockout.
    val alpha = prim.alpha.coerceIn(0f, 1f)
    val blend = prim.blend
    val layered = if (groupSaveCount >= MAX_GROUP_LAYER_DEPTH) {
        Log.status("SafePdfViewer", "group layer depth cap reached, drawing inline")
        false
    } else {
        runCatching {
            groupPaint.reset()
            groupPaint.alpha = (alpha * 255).toInt()
            if (blend != BlendMode.Normal) {
                groupPaint.blendMode = blend.toAndroid()
            }
            nativeCanvas.saveLayer(null, groupPaint)
        }.onFailure {
            Log.status("SafePdfViewer", "GroupPush saveLayer failed", it)
        }.isSuccess
    }
    // Account a level either way, so the matching GroupPop restores this push
    // and never a sibling clip's save.
    if (!layered) nativeCanvas.save()
    groupSaveCount++
}

internal fun SafePdfDrawContext.applyGroupPop() {
    if (groupSaveCount > 0) {
        nativeCanvas.restore()
        groupSaveCount--
    }
}

internal fun SafePdfDrawContext.applySoftMaskPush(prim: PdfPrimitive.SoftMaskPush) {
    // A soft-mask layer is an offscreen buffer, exactly like a transparency-group
    // layer, so it takes the same depth cap. Over the cap — or if the allocation
    // fails — fall back to a plain save so the matching pop still balances, and
    // record that the mask itself has to be discarded rather than painted.
    val frame = SoftMaskFrame(prim.maskType)
    val layered = softMaskStack.size < MAX_GROUP_LAYER_DEPTH &&
        runCatching { nativeCanvas.saveLayer(null, null) }.onFailure {
            Log.status("SafePdfViewer", "SoftMaskPush saveLayer failed", it)
        }.isSuccess
    if (!layered) {
        nativeCanvas.save()
        frame.masked = false
    }
    frame.levels = 1
    softMaskStack.addLast(frame)
}

internal fun SafePdfDrawContext.applySoftMaskTransfer(prim: PdfPrimitive.SoftMaskTransfer) {
    // §11.6.5.2: the mask value passes through /TR. Folded into the matrix that
    // already turns the mask into alpha (see SoftMaskContent), so it is free.
    if (prim.affine) {
        softMaskStack.lastOrNull()?.let { it.trGain = prim.gain; it.trBias = prim.bias }
    }
}

internal fun SafePdfDrawContext.applySoftMaskContent() {
    val frame = softMaskStack.lastOrNull()
    if (frame != null && !frame.masked) {
        // There is no content layer to mask into, so the mask primitives have
        // nowhere to go. Clip them away — letting them paint would draw the mask
        // itself on top of the page, which is worse than an unmasked group.
        nativeCanvas.save()
        nativeCanvas.clipRect(0f, 0f, 0f, 0f)
        frame.levels++
    } else if (frame != null) {
        // The mask layer composites onto the content layer with
        // DST_IN, so the content is kept only where the mask has alpha.
        val maskPaint = android.graphics.Paint()
        maskPaint.blendMode = android.graphics.BlendMode.DST_IN
        nativeCanvas.saveLayer(null, maskPaint)
        frame.levels++
        // Turn the mask into alpha as its layer composites down: Rec.709 luma for
        // a luminosity mask, or the existing alpha, either way through /TR
        // (gain·m + bias). An alpha mask with an identity /TR needs no layer.
        val g = frame.trGain
        val bias = frame.trBias * 255f
        val lm = when {
            frame.maskType == 1 -> android.graphics.ColorMatrix(
                floatArrayOf(
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    g * 0.2126f, g * 0.7152f, g * 0.0722f, 0f, bias,
                )
            )
            g != 1f || bias != 0f -> android.graphics.ColorMatrix(
                floatArrayOf(
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, 0f, 0f,
                    0f, 0f, 0f, g, bias,
                )
            )
            else -> null
        }
        if (lm != null) {
            val lumaPaint = android.graphics.Paint()
            lumaPaint.colorFilter = android.graphics.ColorMatrixColorFilter(lm)
            nativeCanvas.saveLayer(null, lumaPaint)
            frame.levels++
        }
    }
}

internal fun SafePdfDrawContext.applySoftMaskPop() {
    val frame = softMaskStack.removeLastOrNull()
    if (frame != null) {
        repeat(frame.levels) { nativeCanvas.restore() }
    }
}
