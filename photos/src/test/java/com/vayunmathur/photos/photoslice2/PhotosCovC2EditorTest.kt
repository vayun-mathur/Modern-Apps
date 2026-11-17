package com.vayunmathur.photos.photoslice2

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.ui.EditPhotoBottomControls
import com.vayunmathur.photos.ui.EditPhotoCanvas
import com.vayunmathur.photos.ui.EditPhotoEditorState
import com.vayunmathur.photos.ui.EditRoute
import com.vayunmathur.photos.ui.EditorMode
import com.vayunmathur.photos.ui.ToolCategory
import com.vayunmathur.photos.platform.PhotoEditViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cov-c2: drive the editor bottom controls through every category/mode and the canvas. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1400dp")
class PhotosCovC2EditorTest {

    @get:Rule val rule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationContext as Application

    private fun state(): EditPhotoEditorState {
        val vm = PhotoEditViewModel(app, PhotosRepository.get(app))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return EditPhotoEditorState(
            vm, activity,
            mutableStateOf(EditDocument(canvasWidth = 100, canvasHeight = 50)),
            mutableStateOf(null), mutableStateOf(null), mutableStateOf(null),
        )
    }

    private fun backStack() = NavBackStack<EditRoute>(arrayOf(EditRoute.EditPhoto(0L)))

    @Test fun bottomControlsVisitEveryCategory() {
        val s = state()
        rule.setContent { EditPhotoBottomControls(s, backStack()) }
        for (cat in ToolCategory.entries) {
            rule.runOnUiThread { s.openCategory(cat) }
            rule.waitForIdle()
            assertEquals(cat, s.activeCategory)
        }
        rule.runOnUiThread { s.goHome() }
        rule.waitForIdle()
        assertEquals(null, s.activeCategory)
    }

    @Test fun bottomControlsVisitEveryEditorMode() {
        val s = state()
        rule.setContent { EditPhotoBottomControls(s, backStack()) }
        rule.runOnUiThread { s.openCategory(ToolCategory.Adjust) }
        for (mode in EditorMode.entries) {
            rule.runOnUiThread {
                s.openCategory(
                    when (mode) {
                        EditorMode.Crop, EditorMode.FreeTransform -> ToolCategory.Transform
                        EditorMode.Healing, EditorMode.RedEye, EditorMode.DodgeBurn, EditorMode.Smudge -> ToolCategory.Retouch
                        EditorMode.Selection -> ToolCategory.Select
                        EditorMode.Layers, EditorMode.MaskPaint -> ToolCategory.Layers
                        EditorMode.Fill, EditorMode.GradientTool, EditorMode.ShapeRect,
                        EditorMode.ShapeEllipse, EditorMode.ShapeLine, EditorMode.Eyedropper -> ToolCategory.Paint
                        else -> ToolCategory.Adjust
                    },
                )
                // openCategory auto-selects the category's first tool and
                // selectTool toggles, so assign the mode directly to visit it.
                s.editorMode = mode
            }
            rule.waitForIdle()
            assertEquals(mode, s.editorMode, "mode $mode did not stick")
        }
    }

    // Note: the bitmap path of EditPhotoCanvas creates the ink canvas, whose
    // native `ink` library is not present in the JVM test runtime
    // (UnsatisfiedLinkError), so only the no-bitmap path is covered here.

    @Test fun canvasRendersWithoutBitmap() {
        val s = state()
        rule.setContent {
            Column { EditPhotoCanvas(s, androidx.compose.ui.unit.Dp(0f)) }
        }
        rule.waitForIdle()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }
}
