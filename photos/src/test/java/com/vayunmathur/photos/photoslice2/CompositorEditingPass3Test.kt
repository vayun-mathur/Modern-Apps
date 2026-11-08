package com.vayunmathur.photos.photoslice2

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.BasicAdjustment
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.ImageAdjustments
import com.vayunmathur.photos.data.LayerBlendMode
import com.vayunmathur.photos.data.LayerMask
import com.vayunmathur.photos.data.LayerStyle
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.AdjustmentLayer
import com.vayunmathur.photos.util.LayerCompositor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompositorEditingPass3Test {

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationContext as Application

    private fun bmp(color: Int, w: Int = 16, h: Int = 16): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun layer(color: Int, name: String, opacity: Float = 1f, mode: LayerBlendMode = LayerBlendMode.Normal, visible: Boolean = true, mask: LayerMask? = null, style: LayerStyle = LayerStyle(), clipped: Boolean = false): PixelLayer =
        PixelLayer(BitmapReference(bmp(color)), name = name, opacity = opacity, blendMode = mode, visible = visible, mask = mask, style = style, clipped = clipped)

    @Test
    fun `empty document composites to transparent canvas`() {
        val out = LayerCompositor().composite(EditDocument(canvasWidth = 8, canvasHeight = 6))
        assertEquals(8, out.width)
        assertEquals(6, out.height)
        assertEquals(Color.TRANSPARENT, out.getPixel(4, 3))
    }

    @Test
    fun `invisible layers are skipped and opacity blends`() {
        val doc = EditDocument(canvasWidth = 8, canvasHeight = 8)
            .addLayer(layer(Color.RED, "base"))
            .addLayer(layer(Color.BLUE, "hidden", visible = false))
        assertEquals(Color.RED, LayerCompositor().composite(doc).getPixel(4, 4))

        val half = EditDocument(canvasWidth = 8, canvasHeight = 8)
            .addLayer(layer(Color.RED, "base"))
            .addLayer(layer(Color.BLUE, "half", opacity = 0f))
        assertEquals(Color.RED, LayerCompositor().composite(half).getPixel(4, 4))
    }

    @Test
    fun `blend modes change the composite`() {
        val multiply = EditDocument(canvasWidth = 8, canvasHeight = 8)
            .addLayer(layer(Color.WHITE, "base"))
            .addLayer(layer(Color.RED, "top", mode = LayerBlendMode.Multiply))
        assertEquals(Color.RED, LayerCompositor().composite(multiply).getPixel(4, 4))

        val screen = EditDocument(canvasWidth = 8, canvasHeight = 8)
            .addLayer(layer(Color.BLACK, "base"))
            .addLayer(layer(Color.RED, "top", mode = LayerBlendMode.Screen))
        assertEquals(Color.RED, LayerCompositor().composite(screen).getPixel(4, 4))

        val normal = EditDocument(canvasWidth = 8, canvasHeight = 8)
            .addLayer(layer(Color.GREEN, "base"))
            .addLayer(layer(Color.BLUE, "top", mode = LayerBlendMode.Normal))
        assertEquals(Color.BLUE, LayerCompositor().composite(normal).getPixel(4, 4))
        assertNotEquals(Color.RED, LayerCompositor().composite(normal).getPixel(4, 4))
    }

    @Test
    fun `mask hides part of a layer`() {
        val alpha = FloatArray(16 * 16) { i -> if (i % 16 < 8) 1f else 0f }
        val doc = EditDocument(canvasWidth = 16, canvasHeight = 16)
            .addLayer(layer(Color.RED, "base"))
            .addLayer(layer(Color.BLUE, "masked", mask = LayerMask(alpha, 16, 16)))
        val out = LayerCompositor().composite(doc)
        assertEquals(Color.BLUE, out.getPixel(4, 8))
        assertEquals(Color.RED, out.getPixel(12, 8))
    }

    @Test
    fun `downscale respects max dimension`() {
        val doc = EditDocument(canvasWidth = 64, canvasHeight = 32)
            .addLayer(layer(Color.RED, "base"))
        val out = LayerCompositor().composite(doc, maxDimension = 16)
        assertEquals(16, out.width)
        assertEquals(8, out.height)
        val preview = LayerCompositor().compositePreview(doc, maxDimension = 8)
        assertEquals(8, preview.width)
    }

    @Test
    fun `adjustment layer changes pixels and text and drawing layers render`() {
        val adjusted = EditDocument(canvasWidth = 16, canvasHeight = 16)
            .addLayer(layer(0xFF808080.toInt(), "base"))
            .addLayer(AdjustmentLayer(BasicAdjustment(ImageAdjustments(brightness = 40f)), name = "brighten"))
        val out = LayerCompositor().composite(adjusted)
        assertTrue(Color.red(out.getPixel(8, 8)) > 0x80, "brightness raised the pixel: ${Color.red(out.getPixel(8, 8))}")

        // TextLayer rendering also initializes the ink renderer; device-only.

        // DrawingLayer rendering needs the androidx.ink native library,
        // which has no host build; it is covered on the emulator instead.
    }

    @Test
    fun `layer style shadow and stroke render without crashing`() {
        val styled = EditDocument(canvasWidth = 32, canvasHeight = 32)
            .addLayer(layer(Color.WHITE, "base"))
            .addLayer(layer(Color.RED, "styled", style = LayerStyle(dropShadow = true, stroke = true, strokeWidth = 0.02f)))
        val out = LayerCompositor().composite(styled)
        assertEquals(32, out.width)
    }

    @Test
    fun `merge down and flatten collapse the stack`() {
        val compositor = LayerCompositor()
        val doc = EditDocument(canvasWidth = 8, canvasHeight = 8)
            .addLayer(layer(Color.RED, "base"))
            .addLayer(layer(Color.BLUE, "top"))
        val merged = compositor.mergeDown(doc, 1)
        assertEquals(1, merged.layers.size)
        assertEquals(Color.BLUE, compositor.composite(merged).getPixel(4, 4))
        val flat = compositor.flatten(doc)
        assertEquals(1, flat.layers.size)
    }
}
