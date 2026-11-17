package com.vayunmathur.photos.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.DrawingTool
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.Selection
import com.vayunmathur.photos.data.SelectionCombine
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.TextElement
import com.vayunmathur.photos.platform.PhotoEditViewModel
import com.vayunmathur.photos.platform.workInfosOverride
import kotlinx.coroutines.flow.flowOf
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [EditPhotoEditorState] behaviour directly: tool/category switching,
 * recent colours, selection building (marquee/polygon/combine/feather),
 * crop application, overlay commits and the save entry point.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorStateFunctionsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
    }

    private fun state(photo: Photo? = null): Pair<PhotoEditViewModel, EditPhotoEditorState> {
        val vm = PhotoEditViewModel(app, installed.photos)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val state = EditPhotoEditorState(
            vm, activity,
            mutableStateOf(EditDocument(canvasWidth = 100, canvasHeight = 50)),
            mutableStateOf(photo), mutableStateOf<Bitmap?>(null), mutableStateOf(null),
        )
        return vm to state
    }

    private fun photo() = Photo(
        name = "edit.jpg", uri = "content://media/edit", date = 1L, width = 100, height = 50,
        dateModified = 1L, exifSet = true, lat = null, long = null,
        videoData = null, panoData = null,
    )

    @Test fun toolAndCategorySwitching() {
        val (_, state) = state()
        state.selectTool(EditorMode.Adjust)
        assertEquals(EditorMode.Adjust, state.editorMode)
        // Selecting the same tool again toggles home.
        state.selectTool(EditorMode.Adjust)
        assertEquals(EditorMode.None, state.editorMode)
        // Crop enters the cropping preview; another tool exits it.
        state.selectTool(EditorMode.Crop)
        assertEquals(EditorMode.Crop, state.editorMode)
        assertTrue(state.isCropping)
        state.selectTool(EditorMode.Filters)
        assertFalse(state.isCropping)
        for (category in ToolCategory.entries) {
            state.openCategory(category)
            assertEquals(category, state.activeCategory)
        }
        state.openCategory(ToolCategory.Draw)
        assertEquals(DrawingTool.Pointer, state.activeTool)
        state.goHome()
        assertEquals(null, state.activeCategory)
        assertEquals(EditorMode.None, state.editorMode)
    }

    @Test fun recentColorsCappedAtEight() {
        val (_, state) = state()
        val colors = (0 until 12).map { Color(it * 20, it * 10, it) }
        colors.forEach { state.useColor(it) }
        assertEquals(8, state.recentColors.size)
        assertEquals(colors.last(), state.recentColors.first())
        // Re-using a colour moves it to the front without duplicating.
        state.useColor(colors[5])
        assertEquals(colors[5], state.recentColors.first())
        assertEquals(8, state.recentColors.size)
        assertEquals(colors[5], state.paintColor)
    }

    @Test fun selectionBuildingCombineAndFeather() {
        val (vm, state) = state()
        assertEquals(100 to 50, state.selMaskSize())
        state.commitMarquee(Rect(0.1f, 0.1f, 0.5f, 0.6f), isEllipse = false)
        assertNotNull(vm.selection.value)
        assertFalse(vm.selection.value!!.isEmpty())
        // Ellipse marquee replaces (combine mode New by default).
        state.commitMarquee(Rect(0f, 0f, 1f, 1f), isEllipse = true)
        assertNotNull(vm.selection.value)
        // Polygon needs at least three points.
        state.clearSelection()
        assertNull(vm.selection.value)
        state.commitPolygon(listOf(0.1f to 0.1f, 0.9f to 0.1f))
        assertNull(vm.selection.value)
        state.commitPolygon(listOf(0.1f to 0.1f, 0.9f to 0.1f, 0.5f to 0.9f))
        assertNotNull(vm.selection.value)
        // Feather reapplication keeps a selection in place.
        state.selectionFeather = 0.05f
        state.reapplyFeather()
        assertNotNull(vm.selection.value)
        state.selectionCombine = SelectionCombine.Add
        state.commitMarquee(Rect(0.5f, 0.5f, 0.9f, 0.9f), isEllipse = false)
        assertNotNull(vm.selection.value)
        state.clearSelection()
        assertNull(vm.selection.value)
        assertTrue(Selection.rectangle(4, 4, 0f, 0f, 1f, 1f).isEmpty().not())
    }

    @Test fun applyCropRotatesAndCropsDocument() {
        val (vm, state) = state()
        state.selectTool(EditorMode.Crop)
        state.cropAngle = 15f
        state.applyCrop()
        // Crop applied: rotation written back negated, home state restored.
        assertEquals(-15f, vm.document.value.rotation)
        assertEquals(EditorMode.None, state.editorMode)
        assertFalse(state.isCropping)
    }

    @Test fun commitOverlaysMovesTextsIntoLayers() {
        val (vm, state) = state()
        state.texts.add(
            TextElement(id = "x", text = "Hi", x = 0.5f, y = 0.5f, rotation = 0f,
                color = 0xFF000000.toInt(), fontSize = 20f),
        )
        state.selectedTextIndex = 0
        state.commitOverlays()
        assertTrue(state.texts.isEmpty())
        assertNull(state.selectedTextIndex)
        assertTrue(vm.document.value.layers.any { it is com.vayunmathur.photos.data.TextLayer })
        // Nothing pending: a second commit is a no-op.
        val layerCount = vm.document.value.layers.size
        state.commitOverlays()
        assertEquals(layerCount, vm.document.value.layers.size)
    }

    @Test fun doSaveWithoutPhotoIsNoOp() {
        val (_, state) = state(photo = null)
        state.doSave(asCopy = true)
        state.doSave(asCopy = false)
    }
}
