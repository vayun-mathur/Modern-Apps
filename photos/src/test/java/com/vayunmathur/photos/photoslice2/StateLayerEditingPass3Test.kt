package com.vayunmathur.photos.photoslice2

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.AdjustmentLayer
import com.vayunmathur.photos.data.BasicAdjustment
import com.vayunmathur.photos.data.BitmapReference
import com.vayunmathur.photos.data.DrawingLayer
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.GroupInfo
import com.vayunmathur.photos.data.LayerBlendMode
import com.vayunmathur.photos.data.LayerMask
import com.vayunmathur.photos.data.LayerStyle
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.Selection
import com.vayunmathur.photos.data.TextElement
import com.vayunmathur.photos.data.TextLayer
import com.vayunmathur.photos.ui.EditPhotoEditorState
import com.vayunmathur.photos.ui.EditorMode
import com.vayunmathur.photos.ui.ToolCategory
import com.vayunmathur.photos.util.PhotoEditViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StateLayerEditingPass3Test {

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationContext as Application

    private fun state(doc: EditDocument = EditDocument(canvasWidth = 100, canvasHeight = 50)): EditPhotoEditorState {
        val vm = PhotoEditViewModel(app, PhotosRepository.get(app))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return EditPhotoEditorState(
            vm, activity,
            mutableStateOf(doc), mutableStateOf(null), mutableStateOf(null), mutableStateOf(null),
        )
    }

    @Test
    fun `commit overlays folds texts into layers and clears them`() {
        val s = state()
        s.texts.add(
            TextElement(
                id = "ov1", text = "Overlay", x = 0.5f, y = 0.5f,
                rotation = 0f, color = 0xFFFFFFFF.toInt(), fontSize = 20f,
            ),
        )
        s.selectedTextId = "ov1"
        s.commitOverlays()
        assertTrue(s.texts.isEmpty())
        assertNull(s.selectedTextId)
        // Committing again with nothing pending is a no-op.
        s.commitOverlays()
        assertTrue(s.texts.isEmpty())
    }

    @Test
    fun `go home exits crop and resets mode and category`() {
        val s = state()
        s.selectTool(EditorMode.Crop)
        assertTrue(s.isCropping)
        s.activeCategory = ToolCategory.Transform
        s.goHome()
        assertFalse(s.isCropping)
        assertEquals(EditorMode.None, s.editorMode)
        assertEquals(null, s.activeCategory)
    }

    @Test
    fun `open category selects first tool for paint and layers`() {
        val s = state()
        s.openCategory(ToolCategory.Paint)
        assertEquals(EditorMode.Fill, s.editorMode)
        s.openCategory(ToolCategory.Layers)
        assertEquals(EditorMode.Layers, s.editorMode)
        s.openCategory(ToolCategory.Select)
        assertEquals(EditorMode.Selection, s.editorMode)
    }

    @Test
    fun `select tool toggles a non-crop mode off again`() {
        val s = state()
        s.selectTool(EditorMode.Healing)
        assertEquals(EditorMode.Healing, s.editorMode)
        s.selectTool(EditorMode.Healing)
        assertEquals(EditorMode.None, s.editorMode)
    }

    @Test
    fun `selection helpers handle feather clear and short polygons`() {
        val s = state()
        // Fewer than three points never commits.
        s.commitPolygon(listOf(0.1f to 0.1f, 0.2f to 0.2f))
        assertNull(s.vm.selection.value)
        val sel = Selection.rectangle(10, 10, 0.1f, 0.1f, 0.5f, 0.5f)
        s.selectionBase = sel
        s.selectionFeather = 0f
        s.reapplyFeather()
        assertEquals(sel, s.vm.selection.value)
        s.clearSelection()
        assertNull(s.selectionBase)
        assertNull(s.vm.selection.value)
        // Zero-sized canvas still yields a positive mask size.
        val (w, h) = state(EditDocument(canvasWidth = 0, canvasHeight = 0)).selMaskSize()
        assertTrue(w >= 1 && h >= 1)
        val (lw, lh) = state(EditDocument(canvasWidth = 4000, canvasHeight = 2000)).selMaskSize()
        assertTrue(maxOf(lw, lh) <= 768)
        assertEquals(Offset.Zero, s.offset)
    }

    @Test
    fun `layer copyBase updates shared fields and keeps content`() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val pixel = PixelLayer(BitmapReference(bmp), name = "P")
        val renamed = pixel.copyBase(name = "Renamed", visible = false, opacity = 0.5f)
        assertEquals("Renamed", renamed.name)
        assertFalse(renamed.visible)
        assertEquals(0.5f, renamed.opacity)
        assertTrue(renamed is PixelLayer)

        val adj = AdjustmentLayer(BasicAdjustment(), name = "A")
        val adjCopy = adj.copyBase(blendMode = LayerBlendMode.Multiply, locked = true)
        assertEquals(LayerBlendMode.Multiply, adjCopy.blendMode)
        assertTrue(adjCopy.locked)

        val text = TextLayer(
            TextElement(
                id = "lx", text = "T", x = 0f, y = 0f,
                rotation = 0f, color = 0, fontSize = 10f,
            ),
            name = "T",
        )
        assertEquals("Clipped", text.copyBase(name = "Clipped", clipped = true).name)

        val drawing = DrawingLayer(emptyList(), sourceWidth = 10f, sourceHeight = 10f, name = "D")
        assertEquals(0.25f, drawing.copyBase(opacity = 0.25f).opacity)
    }

    @Test
    fun `mask invert and style identity`() {
        val mask = LayerMask(floatArrayOf(0f, 0.5f, 1f), width = 3, height = 1)
        val inverted = mask.invert()
        assertEquals(listOf(1f, 0.5f, 0f), inverted.alphaData.toList())
        val full = LayerMask.full(2, 2)
        assertEquals(listOf(1f, 1f, 1f, 1f), full.alphaData.toList())
        assertTrue(LayerStyle().isIdentity())
        assertFalse(LayerStyle(dropShadow = true).isIdentity())
        assertFalse(LayerStyle(stroke = true).isIdentity())
        assertFalse(LayerStyle(outerGlow = true).isIdentity())
        val group = GroupInfo(name = "G")
        assertTrue(group.visible)
        assertEquals(1f, group.opacity)
        assertEquals(LayerBlendMode.Normal, group.blendMode)
    }
}
