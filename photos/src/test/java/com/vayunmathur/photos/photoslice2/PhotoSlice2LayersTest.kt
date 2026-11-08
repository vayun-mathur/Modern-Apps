package com.vayunmathur.photos.photoslice2

import com.vayunmathur.photos.data.AdjustmentLayer
import com.vayunmathur.photos.data.BasicAdjustment
import com.vayunmathur.photos.data.DrawingLayer
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.GroupInfo
import com.vayunmathur.photos.data.ImageAdjustments
import com.vayunmathur.photos.data.LayerBlendMode
import com.vayunmathur.photos.data.LayerMask
import com.vayunmathur.photos.data.LayerStyle
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.TextElement
import com.vayunmathur.photos.data.TextLayer
import com.vayunmathur.photos.ui.AdjustmentType
import com.vayunmathur.photos.ui.EditorMode
import com.vayunmathur.photos.ui.SelectionTool
import com.vayunmathur.photos.ui.ToolCategory
import com.vayunmathur.photos.ui.categoryTools
import com.vayunmathur.photos.ui.descriptionRes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice-2 tests for the layer-stack model ([EditDocument] mutations, layer
 * [copyBase], adjustment identity) and the editor tool model
 * ([AdjustmentType] get/set, [categoryTools], [EditorMode.descriptionRes]).
 *
 * Bitmap-backed layers use tiny Robolectric bitmaps; everything else is pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoSlice2LayersTest {

    private fun pixelLayer(name: String = "Layer"): PixelLayer {
        val bmp = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xFF112233.toInt())
        return PixelLayer(BitmapReference(bmp), name = name)
    }

    @Test
    fun addLayerSelectsIt() {
        var doc = EditDocument(canvasWidth = 8, canvasHeight = 8)
        assertNull(doc.activeLayer)
        doc = doc.addLayer(pixelLayer("A"))
        assertEquals("A", doc.activeLayer?.name)
        assertEquals(0, doc.activeLayerIndex)
        doc = doc.addLayer(pixelLayer("B"))
        assertEquals("B", doc.activeLayer?.name)
        assertEquals(1, doc.activeLayerIndex)
    }

    @Test
    fun removeLayerKeepsValidActive() {
        var doc = EditDocument(canvasWidth = 8, canvasHeight = 8)
        doc = doc.addLayer(pixelLayer("A")).addLayer(pixelLayer("B")).addLayer(pixelLayer("C"))
        doc = doc.removeLayer(1)
        assertEquals(2, doc.layers.size)
        assertEquals(listOf("A", "C"), doc.layers.map { it.name })
        // Single layer cannot be removed.
        var single = EditDocument(canvasWidth = 8, canvasHeight = 8).addLayer(pixelLayer("only"))
        single = single.removeLayer(0)
        assertEquals(1, single.layers.size)
        // Out-of-range is a no-op.
        assertEquals(doc, doc.removeLayer(99))
    }

    @Test
    fun moveLayerReorders() {
        var doc = EditDocument(canvasWidth = 8, canvasHeight = 8)
        doc = doc.addLayer(pixelLayer("A")).addLayer(pixelLayer("B")).addLayer(pixelLayer("C"))
        doc = doc.setActiveLayer(0).moveLayer(0, 2)
        assertEquals(listOf("B", "C", "A"), doc.layers.map { it.name })
        assertEquals(2, doc.activeLayerIndex)
        // Same-position move is a no-op.
        assertEquals(doc, doc.moveLayer(1, 1))
        assertEquals(doc, doc.moveLayer(99, 0))
    }

    @Test
    fun moveLayerKeepsActiveLayerSelected() {
        var doc = EditDocument(canvasWidth = 8, canvasHeight = 8)
        doc = doc.addLayer(pixelLayer("A")).addLayer(pixelLayer("B"))
            .addLayer(pixelLayer("C")).addLayer(pixelLayer("D"))
        // Active layer below the moved range shifts down with it.
        doc = doc.setActiveLayer(2).moveLayer(0, 3)
        assertEquals(listOf("B", "C", "D", "A"), doc.layers.map { it.name })
        assertEquals("C", doc.activeLayer?.name)
        assertEquals(1, doc.activeLayerIndex)
        // Active layer above the moved range shifts up with it.
        doc = doc.setActiveLayer(1).moveLayer(3, 0)
        assertEquals(listOf("A", "B", "C", "D"), doc.layers.map { it.name })
        assertEquals("C", doc.activeLayer?.name)
        assertEquals(2, doc.activeLayerIndex)
        // Active layer outside the moved range stays put.
        doc = doc.setActiveLayer(3).moveLayer(0, 1)
        assertEquals(listOf("B", "A", "C", "D"), doc.layers.map { it.name })
        assertEquals("D", doc.activeLayer?.name)
        assertEquals(3, doc.activeLayerIndex)
    }

    @Test
    fun updateLayerAndCopyBase() {
        var doc = EditDocument(canvasWidth = 8, canvasHeight = 8).addLayer(pixelLayer("A"))
        doc = doc.updateLayer(0) { it.copyBase(name = "Renamed", opacity = 0.5f, visible = false) }
        val layer = doc.layers[0]
        assertEquals("Renamed", layer.name)
        assertEquals(0.5f, layer.opacity)
        assertFalse(layer.visible)
        // Out-of-range update is a no-op.
        assertEquals(doc, doc.updateLayer(9) { it })
        // Blend mode + lock + clip + group survive copyBase.
        val updated = layer.copyBase(
            blendMode = LayerBlendMode.Multiply, locked = true, clipped = true, groupId = "g1",
        )
        assertEquals(LayerBlendMode.Multiply, updated.blendMode)
        assertTrue(updated.locked)
        assertTrue(updated.clipped)
        assertEquals("g1", updated.groupId)
    }

    @Test
    fun duplicateLayerCopiesAbove() {
        var doc = EditDocument(canvasWidth = 8, canvasHeight = 8)
        doc = doc.addLayer(pixelLayer("A")).addLayer(pixelLayer("B"))
        doc = doc.duplicateLayer(0)
        assertEquals(3, doc.layers.size)
        assertEquals("A copy", doc.layers[1].name)
        assertFalse(doc.layers[1].id == doc.layers[0].id)
        assertEquals(doc.layers[1].id, doc.activeLayer?.id)
        assertEquals(doc, doc.duplicateLayer(42))
    }

    @Test
    fun groupAndUngroup() {
        var doc = EditDocument(canvasWidth = 8, canvasHeight = 8)
        doc = doc.addLayer(pixelLayer("A")).addLayer(pixelLayer("B")).setActiveLayer(1)
        doc = doc.groupActiveWithBelow()
        assertEquals(1, doc.groups.size)
        val gid = doc.groups[0].id
        assertEquals(gid, doc.layers[0].groupId)
        assertEquals(gid, doc.layers[1].groupId)
        doc = doc.ungroupActive()
        assertTrue(doc.groups.isEmpty())
        assertNull(doc.layers[0].groupId)
        // Grouping with nothing below is a no-op.
        val single = EditDocument(canvasWidth = 8, canvasHeight = 8).addLayer(pixelLayer("solo"))
        assertEquals(single, single.groupActiveWithBelow())
    }

    @Test
    fun layerStyleIdentity() {
        assertTrue(LayerStyle().isIdentity())
        assertFalse(LayerStyle(dropShadow = true).isIdentity())
        assertFalse(LayerStyle(stroke = true).isIdentity())
        assertFalse(LayerStyle(outerGlow = true).isIdentity())
    }

    @Test
    fun basicAdjustmentIdentityAndLabel() {
        val identity = BasicAdjustment()
        assertTrue(identity.isIdentity())
        assertEquals("Adjustments", identity.label)
        val tuned = BasicAdjustment(ImageAdjustments(brightness = 10f))
        assertFalse(tuned.isIdentity())
        assertEquals("Adjustments", tuned.label)
    }

    @Test
    fun adjustmentLayerDefaultsNameFromLabel() {
        val layer = AdjustmentLayer(BasicAdjustment())
        assertEquals("Adjustments", layer.name)
        assertTrue(layer.visible)
    }

    @Test
    fun textAndDrawingLayersCarryContent() {
        val el = TextElement(id = "t1", text = "hello", x = 0.5f, y = 0.5f, rotation = 0f, color = 0xFFFFFFFF.toInt(), fontSize = 24f)
        val text = TextLayer(el)
        assertEquals("hello", text.textElement.text)
        val drawing = DrawingLayer(strokes = emptyList(), sourceWidth = 8f, sourceHeight = 8f)
        assertTrue(drawing.strokes.isEmpty())
    }

    @Test
    fun maskInvertRoundTripOnLayer() {
        val doc = EditDocument(canvasWidth = 8, canvasHeight = 8).addLayer(pixelLayer("A"))
        val mask = LayerMask.full(2, 2)
        val withMask = doc.updateLayer(0) { it.copyBase(mask = mask) }
        assertNotNull(withMask.layers[0].mask)
        val cleared = withMask.updateLayer(0) { it.copyBase(mask = null) }
        assertNull(cleared.layers[0].mask)
    }

    @Test
    fun adjustmentTypeGetSetRoundTrip() {
        val base = ImageAdjustments()
        for (type in AdjustmentType.entries) {
            assertEquals(0f, type.get(base), "default ${type.name}")
            val updated = type.set(base, 25f)
            assertEquals(25f, type.get(updated), "round trip ${type.name}")
            // Bounds are sane.
            assertTrue(type.min <= 25f && 25f <= type.max, "bounds ${type.name}")
        }
        // Sharpness/vignette/grain/fade start at 0 min.
        assertEquals(0f, AdjustmentType.Sharpness.min)
        assertEquals(-100f, AdjustmentType.Brightness.min)
    }

    @Test
    fun categoryToolsCoverAllCategories() {
        // Every category except Draw/Select-details has at least one tool.
        for (cat in listOf(ToolCategory.Adjust, ToolCategory.Filters, ToolCategory.Retouch, ToolCategory.Transform, ToolCategory.Layers, ToolCategory.Paint)) {
            assertFalse(categoryTools[cat].isNullOrEmpty(), "tools for $cat")
        }
        assertEquals(ToolCategory.Adjust, ToolCategory.entries.first())
    }

    @Test
    fun selectionToolsEnumerated() {
        assertEquals(5, SelectionTool.entries.size)
        assertTrue(SelectionTool.entries.contains(SelectionTool.Wand))
    }

    @Test
    fun editorModeDescriptionsResolve() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        for (mode in EditorMode.entries) {
            val res = mode.descriptionRes()
            assertTrue(res != 0, "description for $mode")
            // Resolves to a non-empty string.
            assertTrue(ctx.getString(res).isNotEmpty(), "string for $mode")
        }
    }

    @Test
    fun groupInfoDefaults() {
        val g = GroupInfo(name = "My Group")
        assertEquals("My Group", g.name)
        assertTrue(g.visible)
        assertFalse(g.collapsed)
        assertEquals(1f, g.opacity)
    }
}
