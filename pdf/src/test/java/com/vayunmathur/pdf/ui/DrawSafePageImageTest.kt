package com.vayunmathur.pdf.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.vayunmathur.pdf.util.BlendMode
import com.vayunmathur.pdf.util.PdfPrimitive
import com.vayunmathur.pdf.util.SafePdfPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawSafePageImageTest {
    private fun rasterize(page: SafePdfPage): Bitmap {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(canvas), Size(200f, 100f)) {
            drawSafePage(page)
        }
        return bitmap
    }

    private fun solidBitmap(color: Int, w: Int = 10, h: Int = 10): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun nonWhite(bmp: Bitmap): Int {
        var n = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) if (bmp.getPixel(x, y) != Color.WHITE) n++
        return n
    }

    @Test fun imagePrimitivePaintsBitmap() {
        // CTM maps unit square to page rect x 20..120, y 20..70 (page 200x100, origin bottom-left)
        val page = SafePdfPage(200f, 100f, listOf(
            PdfPrimitive.Image(floatArrayOf(100f, 0f, 0f, 50f, 20f, 20f), solidBitmap(Color.RED), alpha = 1f),
        ))
        val out = rasterize(page)
        assertTrue(nonWhite(out) > 1000, "red image should paint, nonWhite=${nonWhite(out)}")
        assertEquals(Color.RED, out.getPixel(60, 40))
    }

    @Test fun imageWithNullOrRecycledBitmapPaintsNothing() {
        val recycled = solidBitmap(Color.RED).apply { recycle() }
        val page = SafePdfPage(200f, 100f, listOf(
            PdfPrimitive.Image(floatArrayOf(100f, 0f, 0f, 50f, 20f, 20f), null),
            PdfPrimitive.Image(floatArrayOf(100f, 0f, 0f, 50f, 20f, 20f), recycled),
        ))
        assertEquals(0, nonWhite(rasterize(page)))
    }

    @Test fun imageAlphaAndBlendAndDegenerateCtm() {
        val page = SafePdfPage(200f, 100f, listOf(
            PdfPrimitive.Image(floatArrayOf(100f, 0f, 0f, 50f, 20f, 20f), solidBitmap(Color.BLUE), alpha = 0.5f, blend = BlendMode.Multiply, interpolate = false),
            PdfPrimitive.Image(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f), solidBitmap(Color.GREEN), alpha = 2f), // degenerate + clamped alpha
        ))
        val out = rasterize(page)
        assertTrue(nonWhite(out) > 0)
        // 50% blue over white is not pure blue
        assertTrue(out.getPixel(60, 40) != Color.BLUE)
    }

    @Test fun tiledImagePaintsPatternAndRejectsEmptyLattice() {
        val cell = solidBitmap(Color.GREEN, 5, 5)
        val page = SafePdfPage(200f, 100f, listOf(
            PdfPrimitive.ImageTiled(floatArrayOf(20f, 0f, 0f, 20f, 20f, 20f), cell, 20f, 20f, 0, 0, 3, 2, alpha = 1f),
            PdfPrimitive.ImageTiled(floatArrayOf(20f, 0f, 0f, 20f, 20f, 20f), cell, 20f, 20f, 0, 0, 0, 0),
            PdfPrimitive.ImageTiled(floatArrayOf(20f, 0f, 0f, 20f, 20f, 20f), null, 20f, 20f, 0, 0, 1, 1),
        ))
        assertTrue(nonWhite(rasterize(page)) > 100)
    }

    @Test fun groupAndClipBracketsWithImage() {
        val page = SafePdfPage(200f, 100f, listOf(
            PdfPrimitive.GroupPush(true, false, 0.8f, BlendMode.Multiply),
            PdfPrimitive.Image(floatArrayOf(100f, 0f, 0f, 50f, 20f, 20f), solidBitmap(Color.RED)),
            PdfPrimitive.GroupPop,
            PdfPrimitive.ClipPush(false, listOf(androidx.compose.ui.geometry.Offset(20f, 20f), androidx.compose.ui.geometry.Offset(120f, 20f), androidx.compose.ui.geometry.Offset(120f, 70f), androidx.compose.ui.geometry.Offset(20f, 70f))),
            PdfPrimitive.FillPath(Color.BLACK, false, listOf(listOf(androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(200f, 0f), androidx.compose.ui.geometry.Offset(200f, 100f), androidx.compose.ui.geometry.Offset(0f, 100f)))),
            PdfPrimitive.ClipPop,
        ))
        assertTrue(nonWhite(rasterize(page)) > 100)
    }
}
