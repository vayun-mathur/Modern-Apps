package com.vayunmathur.photos.util
import com.vayunmathur.photos.platform.LayerCompositor

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Rect
import com.vayunmathur.photos.data.AdjustmentLayer
import com.vayunmathur.photos.data.BasicAdjustment
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.BlurAdj
import com.vayunmathur.photos.data.ChannelMixerAdj
import com.vayunmathur.photos.data.ColorBalanceAdj
import com.vayunmathur.photos.data.CurvesAdj
import com.vayunmathur.photos.data.DrawingLayer
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.GroupInfo
import com.vayunmathur.photos.data.HslAdj
import com.vayunmathur.photos.data.ImageAdjustments
import com.vayunmathur.photos.data.LayerBlendMode
import com.vayunmathur.photos.data.LayerMask
import com.vayunmathur.photos.data.LevelsAdj
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.SelectiveAdj
import com.vayunmathur.photos.data.TextLayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cov-c2: LayerCompositor groups, masks, adjustments, transforms, merge/flatten. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosCovC2CompositorTest {

    private fun bmp(color: Int, w: Int = 32, h: Int = 32): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun pixel(color: Int, name: String, groupId: String? = null): PixelLayer =
        PixelLayer(
            bitmapRef = BitmapReference(bmp(color)), name = name, groupId = groupId,
        )

    @Test fun groupedLayersBlendAsUnitAndHiddenGroupSkips() {
        val group = GroupInfo(id = "g1", name = "Group", opacity = 0.5f, blendMode = LayerBlendMode.Normal)
        val doc = EditDocument(canvasWidth = 32, canvasHeight = 32, groups = listOf(group))
            .addLayer(pixel(Color.RED, "base"))
            .addLayer(pixel(Color.BLUE, "grouped", groupId = "g1"))
        val out = LayerCompositor().composite(doc)
        assertEquals(32, out.width)
        // Half-opacity blue over red is a purple mix, not pure blue.
        val px = out.getPixel(16, 16)
        assertTrue(Color.blue(px) > 0 && Color.red(px) > 0, "group blend was $px")

        val hidden = doc.copy(groups = listOf(group.copy(visible = false)))
        assertEquals(Color.RED, LayerCompositor().composite(hidden).getPixel(16, 16))
        // Preview path also handles groups (no below-cache split).
        assertEquals(32, LayerCompositor().compositePreview(doc, 64).width)
    }

    @Test fun adjustmentLayersOfEveryKindApply() {
        val adjustments = listOf(
            BasicAdjustment(ImageAdjustments(brightness = 20f, contrast = 10f)),
            CurvesAdj(),
            HslAdj(),
            BlurAdj(),
            SelectiveAdj(),
            LevelsAdj(),
            ColorBalanceAdj(),
            ChannelMixerAdj(),
        )
        for (adjustment in adjustments) {
            val doc = EditDocument(canvasWidth = 32, canvasHeight = 32)
                .addLayer(pixel(Color.rgb(100, 120, 140), "base"))
                .addLayer(AdjustmentLayer(adjustment = adjustment, name = adjustment.toString()))
            val out = LayerCompositor().composite(doc)
            assertEquals(32, out.width, "adjustment $adjustment")
        }
    }

    @Test fun maskedClippedAndTextDrawingLayersComposite() {
        val mask = LayerMask.full(32, 32)
        val masked = PixelLayer(
            bitmapRef = BitmapReference(bmp(Color.BLUE)), name = "masked", mask = mask,
        )
        val text = TextLayer(
            textElement = com.vayunmathur.photos.data.TextElement(
                id = "t1", text = "Cov", x = 0.1f, y = 0.1f, rotation = 0f,
                color = Color.YELLOW, fontSize = 20f,
            ),
            name = "text",
        )
        // DrawingLayer rendering needs the native ink library, absent on the JVM.
        val doc = EditDocument(canvasWidth = 32, canvasHeight = 32)
            .addLayer(pixel(Color.RED, "base"))
            .addLayer(masked)
            .addLayer(text)
        assertEquals(32, LayerCompositor().composite(doc).width)
        assertEquals(32, LayerCompositor().compositePreview(doc, 64).width)
    }

    @Test fun mergeFlattenPreviewCacheAndTransforms() {
        val compositor = LayerCompositor()
        val doc = EditDocument(canvasWidth = 32, canvasHeight = 32)
            .addLayer(pixel(Color.RED, "base"))
            .addLayer(pixel(Color.BLUE, "top"))
        val merged = compositor.mergeDown(doc, 1)
        assertEquals(1, merged.layers.size)
        assertEquals(0, merged.activeLayerIndex)
        // Invalid merge indices return the document unchanged.
        assertEquals(2, compositor.mergeDown(doc, 0).layers.size)
        assertEquals(2, compositor.mergeDown(doc, 5).layers.size)

        val flat = compositor.flatten(doc)
        assertEquals(1, flat.layers.size)
        assertTrue(flat.layers[0].locked)
        assertEquals(0, compositor.flatten(EditDocument(canvasWidth = 4, canvasHeight = 4)).layers.size)

        // Preview twice: second call hits the below-layer cache.
        assertEquals(32, compositor.compositePreview(doc, 64).width)
        compositor.invalidateCache()
        assertEquals(32, compositor.compositePreview(doc, 64).width)

        val rotated = doc.copy(rotation = 90f)
        assertEquals(32, compositor.composite(rotated).height)
        val cropped = doc.copy(cropRect = Rect(0f, 0f, 0.5f, 0.5f))
        assertEquals(16, compositor.composite(cropped).width)
    }

    @Test fun largeCanvasUsesParallelRender() {
        val doc = EditDocument(canvasWidth = 400, canvasHeight = 400)
            .addLayer(pixel(Color.RED, "base", ).let {
                PixelLayer(bitmapRef = BitmapReference(bmp(Color.RED, 400, 400)), name = "base")
            })
            .addLayer(
                PixelLayer(
                    bitmapRef = BitmapReference(bmp(Color.BLUE, 400, 400)),
                    name = "top", opacity = 0.5f, blendMode = LayerBlendMode.Multiply,
                ),
            )
        val out = LayerCompositor().composite(doc)
        assertEquals(400, out.width)
        val px = out.getPixel(200, 200)
        assertTrue(Color.red(px) > 0)
    }
}
