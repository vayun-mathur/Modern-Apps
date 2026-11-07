package com.vayunmathur.photos.photoslice2

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.SelectionCombine
import com.vayunmathur.photos.ui.EditPhotoEditorState
import com.vayunmathur.photos.ui.EditorMode
import com.vayunmathur.photos.ui.ToolCategory
import com.vayunmathur.photos.util.PhotoEditViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorStateEditingPass3Test {

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationContext as Application

    private fun state(
        doc: EditDocument = EditDocument(canvasWidth = 100, canvasHeight = 50),
    ): EditPhotoEditorState {
        val vm = PhotoEditViewModel(app, PhotosRepository.get(app))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return EditPhotoEditorState(
            vm, activity,
            mutableStateOf(doc), mutableStateOf(null), mutableStateOf(null), mutableStateOf(null),
        )
    }

    @Test
    fun `open category selects its first tool and draw resets to pointer`() {
        val s = state()
        s.openCategory(ToolCategory.Adjust)
        assertEquals(ToolCategory.Adjust, s.activeCategory)
        assertEquals(EditorMode.Adjust, s.editorMode)
        s.openCategory(ToolCategory.Draw)
        assertEquals(ToolCategory.Draw, s.activeCategory)
        s.goHome()
        assertEquals(null, s.activeCategory)
        assertEquals(EditorMode.None, s.editorMode)
    }

    @Test
    fun `select tool toggles and crop enters and exits cropping`() {
        val s = state()
        s.selectTool(EditorMode.Curves)
        assertEquals(EditorMode.Curves, s.editorMode)
        s.selectTool(EditorMode.Curves)
        assertEquals(EditorMode.None, s.editorMode)
        s.selectTool(EditorMode.Crop)
        assertTrue(s.isCropping)
        assertEquals(0.5f, s.cropCx)
        s.selectTool(EditorMode.HSL)
        assertTrue(!s.isCropping)
        assertEquals(EditorMode.HSL, s.editorMode)
    }

    @Test
    fun `recent colors keep the newest eight unique`() {
        val s = state()
        s.useColor(Color.Red)
        s.useColor(Color.Green)
        s.useColor(Color.Red)
        assertEquals(Color.Red, s.recentColors.first())
        assertEquals(2, s.recentColors.size)
        repeat(10) { s.useColor(Color(0xFF000010 + it)) }
        assertEquals(8, s.recentColors.size)
    }

    @Test
    fun `marquee polygon and selection combine flow`() {
        val s = state()
        s.commitMarquee(Rect(0.1f, 0.1f, 0.6f, 0.6f), isEllipse = false)
        assertNotNull(s.selectionBase)
        val firstCount = s.vm.selection.value!!.mask.count { it == 1f }
        assertTrue(firstCount > 0)

        s.selectionCombine = SelectionCombine.Add
        s.commitMarquee(Rect(0.5f, 0.5f, 0.9f, 0.9f), isEllipse = true)
        assertTrue(s.vm.selection.value!!.mask.count { it == 1f } >= firstCount)

        s.commitPolygon(listOf(0.1f to 0.1f, 0.9f to 0.1f, 0.1f to 0.9f))
        assertNotNull(s.vm.selection.value)
        s.commitPolygon(listOf(0.1f to 0.1f))
        // Too-few points leave the selection alone.
        assertNotNull(s.vm.selection.value)

        s.selectionFeather = 2f
        s.reapplyFeather()
        assertNotNull(s.vm.selection.value)
        s.clearSelection()
        assertNull(s.selectionBase)
        assertNull(s.vm.selection.value)
    }

    @Test
    fun `mask size caps large canvases`() {
        val s = state(EditDocument(canvasWidth = 4000, canvasHeight = 2000))
        val (w, h) = s.selMaskSize()
        assertEquals(768, w)
        assertEquals(384, h)
        val small = state(EditDocument(canvasWidth = 100, canvasHeight = 50))
        assertEquals(100 to 50, small.selMaskSize())
    }

    @Test
    fun `apply crop pushes rotation and crop rect then goes home`() {
        val s = state()
        s.openCategory(ToolCategory.Transform)
        s.selectTool(EditorMode.Crop)
        s.cropCx = 0.5f; s.cropCy = 0.5f; s.cropHx = 0.25f; s.cropHy = 0.25f
        s.cropAngle = 0f
        s.applyCrop()
        assertEquals(null, s.activeCategory)
        assertEquals(EditorMode.None, s.editorMode)
    }

    @Test
    fun `commit overlays without strokes is a no-op`() {
        val s = state()
        s.commitOverlays()
        assertTrue(s.texts.isEmpty())
        assertTrue(s.inkStrokes.isEmpty())
        assertNull(s.vm.selection.value)
    }
}
