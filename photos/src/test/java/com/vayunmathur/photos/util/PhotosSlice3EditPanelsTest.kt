package com.vayunmathur.photos.util

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.LocalNavResultRegistry
import com.vayunmathur.library.util.NavResultRegistry
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.DrawingTool
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.Selection
import com.vayunmathur.photos.data.SelectionCombine
import com.vayunmathur.photos.ui.CategoryBar
import com.vayunmathur.photos.ui.EditPhotoEditorState
import com.vayunmathur.photos.ui.EditorMode
import com.vayunmathur.photos.ui.SelectableChip
import com.vayunmathur.photos.ui.SmallButton
import com.vayunmathur.photos.ui.ToolCategory
import com.vayunmathur.photos.ui.ToolScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 3/3: [EditPhotoEditorState] transitions (previously 0%) plus the
 * shared edit-panel chrome ([CategoryBar], [ToolScreen], [SmallButton],
 * [SelectableChip]). The state holder is built directly on a real
 * [PhotoEditViewModel]; only pure state maths runs — no decode/save paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice3EditPanelsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = context.applicationContext as Application

    private fun str(id: Int): String = context.getString(id)

    private fun state(doc: EditDocument = EditDocument(canvasWidth = 100, canvasHeight = 50)): EditPhotoEditorState {
        val vm = PhotoEditViewModel(app, PhotosRepository.get(app))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return EditPhotoEditorState(
            vm, activity,
            mutableStateOf(doc), mutableStateOf(null), mutableStateOf(null), mutableStateOf(null),
        )
    }

    @Test
    fun defaultsAreNeutral() {
        val s = state()
        assertNull(s.activeCategory)
        assertEquals(EditorMode.None, s.editorMode)
        assertFalse(s.isCropping)
        assertFalse(s.isDrawing)
        assertEquals(0.5f, s.cropCx)
        assertEquals(Color.Red, s.paintColor)
        assertTrue(s.inkStrokes.isEmpty())
        assertEquals(Offset(0f, 0f), s.ftTL)
        assertEquals(Offset(1f, 1f), s.ftBR)
    }

    @Test
    fun selectCropSeedsCenteredCropAndToggleExits() {
        val s = state()
        s.cropCx = 0.2f; s.cropAngle = 30f
        s.selectTool(EditorMode.Crop)
        assertTrue(s.isCropping)
        assertEquals(EditorMode.Crop, s.editorMode)
        assertEquals(0.5f, s.cropCx)
        assertEquals(0f, s.cropAngle)
        assertNull(s.cropAspect)
        // Selecting another tool exits the crop preview first.
        s.selectTool(EditorMode.Adjust)
        assertFalse(s.isCropping)
        assertEquals(EditorMode.Adjust, s.editorMode)
        // Re-selecting the same tool toggles back to None.
        s.selectTool(EditorMode.Adjust)
        assertEquals(EditorMode.None, s.editorMode)
    }

    @Test
    fun useColorCapsRecentColorsAtEightMostRecentFirst() {
        val s = state()
        val colors = (1..10).map { Color(it.toULong() shl 32) }
        colors.forEach { s.useColor(it) }
        assertEquals(8, s.recentColors.size)
        assertEquals(colors.last(), s.recentColors.first())
        assertEquals(colors.last(), s.paintColor)
        // Re-using a color moves it to the front without duplicating.
        s.useColor(colors[5])
        assertEquals(colors[5], s.recentColors.first())
        assertEquals(8, s.recentColors.size)
        assertEquals(1, s.recentColors.count { it == colors[5] })
    }

    @Test
    fun selMaskSizeCapsLongEdgeAt768() {
        assertEquals(100 to 50, state(EditDocument(canvasWidth = 100, canvasHeight = 50)).selMaskSize())
        assertEquals(768 to 384, state(EditDocument(canvasWidth = 2000, canvasHeight = 1000)).selMaskSize())
        assertEquals(1 to 1, state(EditDocument(canvasWidth = 0, canvasHeight = 0)).selMaskSize())
    }

    @Test
    fun marqueeAndPolygonCommitSelections() {
        val s = state()
        s.commitMarquee(Rect(0.1f, 0.2f, 0.5f, 0.6f), isEllipse = false)
        assertNotNull(s.selectionBase)
        assertEquals(100, s.selectionBase!!.width)
        s.clearSelection()
        assertNull(s.selectionBase)
        // Ellipse path and the <3-point polygon guard.
        s.commitMarquee(Rect(0f, 0f, 1f, 1f), isEllipse = true)
        assertNotNull(s.selectionBase)
        s.clearSelection()
        s.commitPolygon(listOf(0f to 0f, 1f to 1f))
        assertNull(s.selectionBase)
        s.commitPolygon(listOf(0f to 0f, 1f to 0f, 1f to 1f))
        assertNotNull(s.selectionBase)
    }

    @Test
    fun applySelectionCombinesWithBaseUnlessNew() {
        val s = state()
        val a = Selection.rectangle(100, 50, 0f, 0f, 0.4f, 1f)
        val b = Selection.rectangle(100, 50, 0.6f, 0f, 1f, 1f)
        s.selectionCombine = SelectionCombine.New
        s.applySelection(a)
        assertEquals(a, s.selectionBase)
        s.selectionCombine = SelectionCombine.Add
        s.applySelection(b)
        assertNotNull(s.selectionBase)
        assertTrue(s.selectionBase!!.width == 100)
    }

    @Test
    fun openCategoryAndGoHomeResetToolState() {
        val s = state()
        s.openCategory(ToolCategory.Draw)
        assertEquals(ToolCategory.Draw, s.activeCategory)
        assertTrue(s.isDrawing)
        assertEquals(DrawingTool.Pointer, s.activeTool)
        s.editorMode = EditorMode.Crop; s.isCropping = true
        s.goHome()
        assertNull(s.activeCategory)
        assertEquals(EditorMode.None, s.editorMode)
        assertFalse(s.isCropping)
        // Non-draw categories auto-select their first tool.
        s.openCategory(ToolCategory.Select)
        assertEquals(ToolCategory.Select, s.activeCategory)
        assertEquals(EditorMode.Selection, s.editorMode)
    }

    @Test
    fun applyCropProducesInBoundsRectAndGoesHome() {
        val s = state()
        s.selectTool(EditorMode.Crop)
        s.cropCx = 0.5f; s.cropCy = 0.5f; s.cropHx = 0.25f; s.cropHy = 0.25f; s.cropAngle = 0f
        s.applyCrop()
        assertNull(s.activeCategory)
        assertEquals(EditorMode.None, s.editorMode)
    }

    // ---- Compose chrome ----

    @Test
    fun categoryBarRendersAllCategoriesAndReportsClicks() {
        var picked: ToolCategory? = null
        composeRule.setContent { CategoryBar { picked = it } }
        ToolCategory.entries.forEach { composeRule.onNodeWithText(str(it.labelRes)).assertExists() }
        composeRule.onNodeWithText(str(ToolCategory.Draw.labelRes)).performClick()
        assertEquals(ToolCategory.Draw, picked)
    }

    @Test
    fun toolScreenShowsToolsAndBackFires() {
        var backed = false
        var tool: EditorMode? = null
        composeRule.setContent {
            ToolScreen(ToolCategory.Transform, EditorMode.Crop, { tool = it }, { backed = true }) {}
        }
        composeRule.onNodeWithText(str(ToolCategory.Transform.labelRes)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.tool_transform)).performClick()
        assertEquals(EditorMode.FreeTransform, tool)
        assertFalse(backed)
    }

    @Test
    fun vaultViewerPageShowsNotFoundForUnknownId() {
        val stack = com.vayunmathur.library.util.NavBackStack<com.vayunmathur.photos.Route>(
            arrayOf(com.vayunmathur.photos.Route.Gallery),
        )
        val vm = SecureFolderViewModel(app)
        composeRule.setContent {
            com.vayunmathur.photos.ui.VaultViewerPage(stack, vaultId = 999L, password = "pw", vm)
        }
        composeRule.onNodeWithText(str(R.string.vault_viewer_not_found)).assertIsDisplayed()
    }

    @Test
    fun drawingSettingsPenShowsThicknessAndColor() {
        val stack = com.vayunmathur.library.util.NavBackStack<com.vayunmathur.photos.ui.EditRoute>(
            arrayOf(com.vayunmathur.photos.ui.EditRoute.EditPhoto(1L)),
        )
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                com.vayunmathur.photos.ui.DrawingSettingsPage(stack, DrawingTool.Pen, 0xFF0000, 10f, 0.5f)
            }
        }
        composeRule.onNodeWithText(str(R.string.thickness)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.color)).assertIsDisplayed()
    }

    @Test
    fun drawingSettingsHighlighterShowsOpacity() {
        val stack = com.vayunmathur.library.util.NavBackStack<com.vayunmathur.photos.ui.EditRoute>(
            arrayOf(com.vayunmathur.photos.ui.EditRoute.EditPhoto(1L)),
        )
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                com.vayunmathur.photos.ui.DrawingSettingsPage(stack, DrawingTool.Highlighter, 0xFFFF00, 20f, 0.5f)
            }
        }
        composeRule.onNodeWithText(str(R.string.opacity)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.color)).assertIsDisplayed()
    }

    @Test
    fun drawingSettingsEraserHidesColor() {
        val stack = com.vayunmathur.library.util.NavBackStack<com.vayunmathur.photos.ui.EditRoute>(
            arrayOf(com.vayunmathur.photos.ui.EditRoute.EditPhoto(1L)),
        )
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalNavResultRegistry provides NavResultRegistry()) {
                com.vayunmathur.photos.ui.DrawingSettingsPage(stack, DrawingTool.Eraser, 0, 10f, 1f)
            }
        }
        composeRule.onNodeWithText(str(R.string.thickness)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.color)).assertDoesNotExist()
    }

    @Test
    fun smallButtonRespectsEnabledAndChipSelects() {
        var clicks = 0
        composeRule.setContent {
            SmallButton("Apply", enabled = false) { clicks++ }
            SelectableChip("Rect", selected = true, onClick = { clicks += 10 })
        }
        composeRule.onNodeWithText("Apply").assertExists()
        composeRule.onNodeWithText("Rect").performClick()
        assertEquals(10, clicks)
    }
}
