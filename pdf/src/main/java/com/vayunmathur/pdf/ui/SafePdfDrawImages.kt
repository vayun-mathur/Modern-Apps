package com.vayunmathur.pdf.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidPath
import com.vayunmathur.library.log.Log
import com.vayunmathur.pdf.platform.BlendMode
import com.vayunmathur.pdf.platform.PdfPrimitive

/** Image + tiled-image writers for [SafePdfDrawContext]. */
internal fun SafePdfDrawContext.drawImagePrim(prim: PdfPrimitive.Image) {
    val bmp = prim.bitmap?.takeUnless { it.isRecycled } ?: return
    val matrixAlpha = prim.alpha.coerceIn(0f, 1f)
    if (matrixAlpha < 1f) {
        imagePaint.alpha = (matrixAlpha * 255).toInt()
    } else {
        imagePaint.alpha = 255
    }
    val m = prim.ctm
    // PDF 32000-1 §8.9.5.1 Table 89: /Interpolate defaults to false, and bilevel
    // art (stencil masks, 1-bit scans, fax) must never be smoothed — bilinear
    // filtering turns crisp black-and-white line art into grey fringes. Rust makes
    // the per-image decision in image_should_interpolate; honour it here instead of
    // filtering everything.
    imagePaint.isFilterBitmap = prim.interpolate
    fun unitToCanvas(u: Float, v: Float): Offset {
        val pageX = m[0] * u + m[2] * v + m[4]
        val pageY = m[1] * u + m[3] * v + m[5]
        return map(Offset(pageX, pageY))
    }
    val bw = bmp.width.toFloat()
    val bh = bmp.height.toFloat()
    imgSrc[0] = 0f; imgSrc[1] = 0f; imgSrc[2] = bw; imgSrc[3] = 0f
    imgSrc[4] = bw; imgSrc[5] = bh; imgSrc[6] = 0f; imgSrc[7] = bh
    val c00 = unitToCanvas(0f, 1f)
    val c10 = unitToCanvas(1f, 1f)
    val c11 = unitToCanvas(1f, 0f)
    val c01 = unitToCanvas(0f, 0f)
    imgDst[0] = c00.x; imgDst[1] = c00.y; imgDst[2] = c10.x; imgDst[3] = c10.y
    imgDst[4] = c11.x; imgDst[5] = c11.y; imgDst[6] = c01.x; imgDst[7] = c01.y
    // setPolyToPoly leaves the matrix untouched when the destination quad is
    // degenerate (zero-area or collinear), so it must be reset first: a stale or
    // identity matrix would paint the bitmap unscaled at the canvas origin over
    // real content. A zero-area image is unpaintable per PDF 32000-1 8.9.5.2.
    imgMatrix.reset()
    if (!imgMatrix.setPolyToPoly(imgSrc, 0, imgDst, 0, 4)) return
    imagePaint.setBlend(prim.blend)
    nativeCanvas.drawBitmap(bmp, imgMatrix, imagePaint)
    imagePaint.setBlend(BlendMode.Normal)
    imagePaint.alpha = 255
}

internal fun SafePdfDrawContext.drawImageTiledPrim(prim: PdfPrimitive.ImageTiled) {
    val bmp = prim.bitmap?.takeUnless { it.isRecycled } ?: return
    val m = prim.ctm
    fun cellToCanvas(u: Float, v: Float): Offset {
        val pageX = m[0] * u + m[2] * v + m[4]
        val pageY = m[1] * u + m[3] * v + m[5]
        return map(Offset(pageX, pageY))
    }
    val bw = bmp.width.toFloat()
    val bh = bmp.height.toFloat()
    // Same unit-square convention as Image: bitmap row 0 is the top, which is
    // v = 1 in PDF space. This matrix maps cell bitmap pixels to the canvas, and
    // TileMode.REPEAT then extends it along the bitmap's own axes — which under
    // this matrix are the pattern lattice's axes, so rotation and shear come free.
    imgSrc[0] = 0f; imgSrc[1] = 0f; imgSrc[2] = bw; imgSrc[3] = 0f
    imgSrc[4] = bw; imgSrc[5] = bh; imgSrc[6] = 0f; imgSrc[7] = bh
    val t00 = cellToCanvas(0f, 1f)
    val t10 = cellToCanvas(1f, 1f)
    val t11 = cellToCanvas(1f, 0f)
    val t01 = cellToCanvas(0f, 0f)
    imgDst[0] = t00.x; imgDst[1] = t00.y; imgDst[2] = t10.x; imgDst[3] = t10.y
    imgDst[4] = t11.x; imgDst[5] = t11.y; imgDst[6] = t01.x; imgDst[7] = t01.y
    imgMatrix.reset()
    if (!imgMatrix.setPolyToPoly(imgSrc, 0, imgDst, 0, 4)) return
    if (prim.nx <= 0 || prim.ny <= 0) return

    // The shader repeats without bound, so the lattice extent only decides the
    // region to cover. Under a rotated or sheared ctm that region is a
    // parallelogram, not a rect, so fill the mapped quad rather than its bounds.
    val u0 = prim.i0.toFloat()
    val v0 = prim.j0.toFloat()
    val u1 = u0 + prim.nx.toFloat()
    val v1 = v0 + prim.ny.toFloat()
    val r00 = cellToCanvas(u0, v0)
    val r10 = cellToCanvas(u1, v0)
    val r11 = cellToCanvas(u1, v1)
    val r01 = cellToCanvas(u0, v1)
    reusePath.reset()
    reusePath.moveTo(r00.x, r00.y)
    reusePath.lineTo(r10.x, r10.y)
    reusePath.lineTo(r11.x, r11.y)
    reusePath.lineTo(r01.x, r01.y)
    reusePath.close()

    val shader = runCatching {
        android.graphics.BitmapShader(
            bmp,
            android.graphics.Shader.TileMode.REPEAT,
            android.graphics.Shader.TileMode.REPEAT,
        ).also { it.setLocalMatrix(imgMatrix) }
    }.onFailure {
        Log.status("SafePdfViewer", "tiling pattern shader failed", it)
    }.getOrNull() ?: return

    tilePaint.reset()
    tilePaint.isAntiAlias = true
    tilePaint.isFilterBitmap = true
    tilePaint.shader = shader
    tilePaint.alpha = (prim.alpha.coerceIn(0f, 1f) * 255).toInt()
    tilePaint.setBlend(prim.blend)
    nativeCanvas.drawPath(reusePath.asAndroidPath(), tilePaint)
    tilePaint.shader = null
}
