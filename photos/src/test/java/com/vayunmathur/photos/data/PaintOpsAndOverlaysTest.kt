package com.vayunmathur.photos.data
import com.vayunmathur.photos.platform.drawSerializedStrokes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.vayunmathur.library.image.Size
import com.vayunmathur.photos.platform.FaceCropTransformation
import com.vayunmathur.photos.platform.drawTextElement
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Paint operations (flood fill, gradient, shapes), the face-crop
 * transformation, and the canvas overlay renderers, all on real bitmaps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaintOpsAndOverlaysTest {

    private fun twoTone(): Bitmap {
        val bmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        for (x in 0 until 20) for (y in 0 until 20) {
            bmp.setPixel(x, y, if (x < 10) Color.RED else Color.BLUE)
        }
        return bmp
    }

    @Test fun floodFillRespectsToleranceBoundary() {
        val filled = floodFillBitmap(twoTone(), 0.1f, 0.5f, Color.GREEN, tolerance = 0.05f)
        assertEquals(Color.GREEN, filled.getPixel(2, 10))
        // The blue half is beyond tolerance and stays blue.
        assertEquals(Color.BLUE, filled.getPixel(18, 10))
        // A loose tolerance floods everything reachable.
        val flooded = floodFillBitmap(twoTone(), 0.1f, 0.5f, Color.GREEN, tolerance = 1f)
        assertEquals(Color.GREEN, flooded.getPixel(18, 10))
    }

    @Test fun gradientAndShapesDraw() {
        val gradient = drawGradientBitmap(twoTone(), 0f, 0f, 1f, 0f, Color.WHITE)
        assertEquals(20, gradient.width)
        val rect = drawShapeBitmap(twoTone(), PaintShape.Rectangle, 0f, 0f, 0.5f, 0.5f, Color.GREEN, 0.1f)
        assertEquals(Color.GREEN, rect.getPixel(5, 5))
        assertEquals(Color.BLUE, rect.getPixel(18, 10))
        val ellipse = drawShapeBitmap(twoTone(), PaintShape.Ellipse, 0f, 0f, 1f, 1f, Color.YELLOW, 0.1f)
        assertEquals(Color.YELLOW, ellipse.getPixel(10, 10))
        val line = drawShapeBitmap(twoTone(), PaintShape.Line, 0f, 0f, 1f, 1f, Color.BLACK, 0.05f)
        assertEquals(20, line.height)
    }

    @Test fun faceCropTransformationCropsAndClamps() = runBlocking {
        val src = twoTone()
        val crop = FaceCropTransformation(0f, 0f, 0.5f, 1f).transform(src, Size(10, 20))
        assertEquals(10, crop.width)
        assertEquals(Color.RED, crop.getPixel(5, 10))
        // Out-of-range boxes clamp into the bitmap.
        val clamped = FaceCropTransformation(-1f, -1f, 2f, 2f).transform(src, Size(20, 20))
        assertEquals(20, clamped.width)
        assertEquals(
            FaceCropTransformation(0f, 0f, 0.5f, 1f).cacheKey,
            FaceCropTransformation(0f, 0f, 0.5f, 1f).cacheKey,
        )
        Unit
    }

    @Test fun overlayRenderersDrawOntoCanvas() {
        val target = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val canvas = Canvas(target)
        canvas.drawTextElement(
            TextElement(id = "t", text = "Hello", x = 0.1f, y = 0.1f, rotation = 0f,
                color = Color.BLACK, fontSize = 20f),
            canvasWidth = 100, canvasHeight = 80, referenceWidth = 100f,
        )
        // drawSerializedStrokes needs the native ink renderer, which has
        // no host build; it runs on device.
        // Something was drawn over the white background.
        var nonWhite = 0
        for (x in 0 until 100) for (y in 0 until 80) {
            if (target.getPixel(x, y) != Color.WHITE) nonWhite++
        }
        assertTrue(nonWhite > 0)
    }
}
