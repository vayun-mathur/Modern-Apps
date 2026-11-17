package com.vayunmathur.pdf.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.vayunmathur.pdf.platform.PathOp
import com.vayunmathur.pdf.platform.PdfPrimitive
import com.vayunmathur.pdf.platform.SafePdfPage

/**
 * Cap on nested transparency-group layers. Each one is an offscreen buffer, so unlike a
 * plain clip save it has a real memory cost. Rust bounds group depth at 32; exceeding it
 * must never abandon the page, so the push is still accounted (its matching pop then
 * restores the right level) and only the layer itself is skipped.
 */
internal const val MAX_GROUP_LAYER_DEPTH = 32

/**
 * Shared mutable render state for [drawSafePage], threaded through the per-kind
 * writer extensions in SafePdfDrawFills, SafePdfDrawStrokes, SafePdfDrawText,
 * SafePdfDrawImages and SafePdfDrawState.
 *
 * The split is purely organizational: `drawSafePage` runs on every frame at
 * every zoom level, so the Path/Matrix/Paint reuse discipline below is
 * load-bearing. Canvas.drawPath/drawBitmap and saveLayer copy what they need
 * into the display list, so reuse is safe.
 */
internal class SafePdfDrawContext(val scope: DrawScope, pageWidth: Float) {
    val scale: Float = scope.size.width / pageWidth
    val h: Float = scope.size.height

    fun map(p: Offset) = Offset(p.x * scale, h - p.y * scale)

    val textPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        isDither = true
        isSubpixelText = true
        isLinearText = true
    }
    val textStrokePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        isDither = true
        isSubpixelText = true
        isLinearText = true
        style = android.graphics.Paint.Style.STROKE
    }
    val imagePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }

    val nativeCanvas: android.graphics.Canvas get() = scope.drawContext.canvas.nativeCanvas
    var saveCount = 0
    val clipPath = android.graphics.Path()
    var groupSaveCount = 0

    // Active ExtGState soft-mask brackets.
    inner class SoftMaskFrame(val maskType: Int) {
        /** Canvas levels this bracket has opened, so SoftMaskPop restores exactly them. */
        var levels = 0
        /** False when the content layer could not be allocated, in which case the mask
         *  primitives must be discarded rather than painted onto the page. */
        var masked = true
        /** /TR as `gain * m + bias`; identity until a SoftMaskTransfer arrives. */
        var trGain = 1f
        var trBias = 0f
    }
    val softMaskStack = ArrayDeque<SoftMaskFrame>()

    // Reused across primitives (see class KDoc for why).
    val reusePath = Path()
    val pathOpsPath = android.graphics.Path()
    val imgMatrix = android.graphics.Matrix()
    val imgSrc = FloatArray(8)
    val imgDst = FloatArray(8)
    val groupPaint = android.graphics.Paint()
    val tilePaint = android.graphics.Paint()

    // Bezier-retentive clip path (v4) and text-clip accumulation (Tr 4-7).
    fun pathOpsToPath(ops: List<PathOp>): android.graphics.Path {
        val p = pathOpsPath
        p.reset()
        for (op in ops) {
            when (op) {
                is PathOp.Move -> { val o = map(Offset(op.x, op.y)); p.moveTo(o.x, o.y) }
                is PathOp.Line -> { val o = map(Offset(op.x, op.y)); p.lineTo(o.x, o.y) }
                is PathOp.Cubic -> {
                    val a = map(Offset(op.x1, op.y1))
                    val b = map(Offset(op.x2, op.y2))
                    val c = map(Offset(op.x3, op.y3))
                    p.cubicTo(a.x, a.y, b.x, b.y, c.x, c.y)
                }
                PathOp.Close -> p.close()
            }
        }
        return p
    }
    val textClipPath = android.graphics.Path()
    val glyphPathPaint = android.graphics.Paint().apply { isAntiAlias = true }
    val tmpGlyphPath = android.graphics.Path()

    fun drain() {
        // Ensure balanced restore. NOTE: this is the normal-path drain only — it does NOT run if a
        // primitive throws, so a caller must anchor on `nativeCanvas.saveCount` and
        // `restoreToCount` it in a finally. Both callers do: SafePdfPageCanvas and CutGlueScreen.
        while (softMaskStack.isNotEmpty()) {
            val frame = softMaskStack.removeLast()
            repeat(frame.levels) { nativeCanvas.restore() }
        }
        while (groupSaveCount > 0) {
            nativeCanvas.restore()
            groupSaveCount--
        }
        while (saveCount > 0) {
            nativeCanvas.restore()
            saveCount--
        }
    }
}

/**
 * Draw a page's primitives, mapping PDF page space (origin bottom-left) to the
 * canvas (origin top-left) with a uniform fit-to-width scale + Y-flip.
 *
 * Delegates per-kind to the writer extensions so each file stays under the
 * FileLength limit; logic is verbatim from the original single function.
 */
internal fun DrawScope.drawSafePage(page: SafePdfPage) {
    val ctx = SafePdfDrawContext(this, page.width)
    for (prim in page.primitives) {
        when (prim) {
            is PdfPrimitive.FillPath -> ctx.drawFillPath(prim)
            is PdfPrimitive.StrokePath -> ctx.drawStrokePath(prim)
            is PdfPrimitive.Text -> ctx.drawTextPrim(prim)
            is PdfPrimitive.Image -> ctx.drawImagePrim(prim)
            is PdfPrimitive.ImageTiled -> ctx.drawImageTiledPrim(prim)
            is PdfPrimitive.ClipPush -> ctx.applyClipPush(prim)
            is PdfPrimitive.ClipPop -> ctx.applyClipPop()
            is PdfPrimitive.TextClipApply -> ctx.applyTextClip()
            is PdfPrimitive.GroupPush -> ctx.applyGroupPush(prim)
            is PdfPrimitive.GroupPop -> ctx.applyGroupPop()
            is PdfPrimitive.SoftMaskPush -> ctx.applySoftMaskPush(prim)
            is PdfPrimitive.SoftMaskTransfer -> ctx.applySoftMaskTransfer(prim)
            is PdfPrimitive.SoftMaskContent -> ctx.applySoftMaskContent()
            is PdfPrimitive.SoftMaskPop -> ctx.applySoftMaskPop()
        }
    }
    ctx.drain()
}
