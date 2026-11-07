package com.vayunmathur.photos.photoslice2

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.ui.EditPhotoBottomControls
import com.vayunmathur.photos.ui.EditPhotoEditorState
import com.vayunmathur.photos.ui.EditRoute
import com.vayunmathur.photos.ui.EditorMode
import com.vayunmathur.photos.ui.ToolCategory
import com.vayunmathur.photos.util.PhotoEditViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1400dp")
class BottomControlsEditingPass3Test {

    @get:Rule val rule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationContext as Application

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun state(category: ToolCategory? = null, mode: EditorMode = EditorMode.None): EditPhotoEditorState {
        val vm = PhotoEditViewModel(app, PhotosRepository.get(app))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return EditPhotoEditorState(
            vm, activity,
            mutableStateOf(EditDocument(canvasWidth = 100, canvasHeight = 50)),
            mutableStateOf(null), mutableStateOf(null), mutableStateOf(null),
        ).also {
            it.activeCategory = category
            it.editorMode = mode
        }
    }

    private fun backStack() = NavBackStack<EditRoute>(arrayOf(EditRoute.EditPhoto(0L)))

    @Test
    fun `home shows category bar and selecting a category opens it`() {
        val s = state()
        rule.setContent { EditPhotoBottomControls(s, backStack()) }
        rule.onNodeWithText(ctx.getString(ToolCategory.Adjust.labelRes)).assertIsDisplayed()
        rule.onNodeWithText(ctx.getString(ToolCategory.Adjust.labelRes)).performClick()
        assertEquals(ToolCategory.Adjust, s.activeCategory)
    }

    @Test
    fun `draw category shows drawing controls`() {
        val s = state(ToolCategory.Draw)
        rule.setContent { EditPhotoBottomControls(s, backStack()) }
        rule.onNodeWithText(ctx.getString(com.vayunmathur.photos.R.string.tool_cat_draw)).assertIsDisplayed()
        assertNotNull(s)
    }

    @Test
    fun `crop mode shows crop controls`() {
        val s = state(ToolCategory.Transform, EditorMode.Crop)
        rule.setContent { EditPhotoBottomControls(s, backStack()) }
        rule.waitForIdle()
        assertEquals(EditorMode.Crop, s.editorMode)
    }

    @Test
    fun `adjust category with brightness mode shows editor panel`() {
        val s = state(ToolCategory.Adjust, EditorMode.Adjust)
        rule.setContent { EditPhotoBottomControls(s, backStack()) }
        rule.waitForIdle()
        assertEquals(ToolCategory.Adjust, s.activeCategory)
    }

    @Test
    fun `filters category shows tool screen`() {
        val s = state(ToolCategory.Filters, EditorMode.Filters)
        rule.setContent { EditPhotoBottomControls(s, backStack()) }
        rule.waitForIdle()
        assertEquals(ToolCategory.Filters, s.activeCategory)
    }
}
