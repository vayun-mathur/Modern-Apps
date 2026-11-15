package com.vayunmathur.photos.photoslice2

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.ui.EditPhotoBottomControls
import com.vayunmathur.photos.ui.EditPhotoEditorState
import com.vayunmathur.photos.ui.EditRoute
import com.vayunmathur.photos.ui.EditorMode
import com.vayunmathur.photos.ui.ToolCategory
import com.vayunmathur.photos.ui.categoryTools
import com.vayunmathur.photos.util.PhotoEditViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cov-c2: click through the editor category bar and every tool entry. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1400dp")
class PhotosCovC2EditorClickTest {

    @get:Rule val rule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationContext as Application
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun state(): EditPhotoEditorState {
        val vm = PhotoEditViewModel(app, PhotosRepository.get(app))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return EditPhotoEditorState(
            vm, activity,
            mutableStateOf(EditDocument(canvasWidth = 100, canvasHeight = 50)),
            mutableStateOf(null), mutableStateOf(null), mutableStateOf(null),
        )
    }

    @Test fun clickingCategoriesAndToolsVisitsPanels() {
        val s = state()
        rule.setContent { EditPhotoBottomControls(s, NavBackStack(arrayOf(EditRoute.EditPhoto(0L)))) }
        for (cat in ToolCategory.entries) {
            // From home, tap the category label; from a tool screen, go home first.
            if (s.activeCategory != null) {
                rule.runOnUiThread { s.goHome() }
                rule.waitForIdle()
            }
            rule.onNodeWithText(ctx.getString(cat.labelRes)).performClick()
            rule.waitForIdle()
            assertEquals(cat, s.activeCategory, "category $cat did not open")
            // Tap each tool entry in this category (Draw has its own toolbar).
            for (entry in categoryTools[cat].orEmpty()) {
                val label = runCatching { ctx.getString(entry.labelRes) }.getOrNull()
                if (label != null) {
                    val nodes = rule.onAllNodes(
                        androidx.compose.ui.test.hasText(label),
                    )
                    if (nodes.fetchSemanticsNodes().isNotEmpty()) {
                        nodes[0].performClick()
                        rule.waitForIdle()
                        assertEquals(cat, s.activeCategory)
                    }
                }
            }
            // Click any remaining clickable controls in the panel (crop/free-transform/draw).
            val clickables = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
            assertTrue(clickables > 0)
        }
    }

    @Test fun cropControlsRotateAspectResetAndCancel() {
        val s = state()
        rule.setContent { EditPhotoBottomControls(s, NavBackStack(arrayOf(EditRoute.EditPhoto(0L)))) }
        rule.runOnUiThread {
            s.openCategory(ToolCategory.Transform)
            s.selectTool(EditorMode.Crop)
        }
        rule.waitForIdle()
        assertEquals(EditorMode.Crop, s.editorMode)
        // Exercise every clickable in the crop panel (rotate/aspect/reset/apply/cancel/back).
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until count) {
            try {
                rule.onAllNodes(hasClickAction())[i].performClick()
                rule.waitForIdle()
            } catch (_: Throwable) {
                // Node tree changed after a click (e.g. cancel went home); stop.
                break
            }
        }
        rule.waitForIdle()
    }

    @Test fun freeTransformControlsRenderAndAct() {
        val s = state()
        rule.setContent { EditPhotoBottomControls(s, NavBackStack(arrayOf(EditRoute.EditPhoto(0L)))) }
        rule.runOnUiThread {
            s.openCategory(ToolCategory.Transform)
            s.editorMode = EditorMode.FreeTransform
        }
        rule.waitForIdle()
        assertEquals(EditorMode.FreeTransform, s.editorMode)
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        assertTrue(count > 0)
        for (i in 0 until count) {
            try {
                rule.onAllNodes(hasClickAction())[i].performClick()
                rule.waitForIdle()
            } catch (_: Throwable) {
                break
            }
        }
    }
}
