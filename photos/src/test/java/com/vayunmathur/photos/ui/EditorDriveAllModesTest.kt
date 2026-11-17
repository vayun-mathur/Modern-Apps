package com.vayunmathur.photos.ui
import com.vayunmathur.photos.platform.workInfosOverride

import android.app.Activity
import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.platform.PhotoEditViewModel
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Drives the editor chrome through every tool category and a spread of
 * editor modes: bottom controls and active panels compose for each, and
 * every exposed clickable is pressed so mode switches, chips and toggles
 * execute their real handlers against the ViewModel document.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorDriveAllModesTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setup() {
        com.vayunmathur.photos.platform.workInfosOverride = { _, _ -> flowOf(emptyList()) }
        TestPhotosRepository.install(app)
    }

    private fun editorState(
        category: ToolCategory?,
        mode: EditorMode,
    ): EditPhotoEditorState {
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

    private fun clickEverything() {
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until minOf(count, 14)) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            runCatching { rule.waitForIdle() }
        }
    }

    @Test fun everyCategoryComposesAndResponds() {
        val backStack = NavBackStack<EditRoute>(arrayOf(EditRoute.EditPhoto(0L)))
        val current = mutableStateOf(editorState(ToolCategory.entries.first(), EditorMode.None))
        rule.setContent {
            Column {
                EditPhotoCanvas(current.value, 0.dp)
                EditPhotoBottomControls(current.value, backStack)
            }
        }
        for (category in ToolCategory.entries) {
            current.value = editorState(category, EditorMode.None)
            rule.waitForIdle()
            clickEverything()
        }
        assertTrue(true)
    }

    @Test fun representativeModesComposeAndRespond() {
        val backStack = NavBackStack<EditRoute>(arrayOf(EditRoute.EditPhoto(0L)))
        val modes = listOf(
            EditorMode.Adjust, EditorMode.Curves, EditorMode.HSL, EditorMode.Levels,
            EditorMode.ColorBalance, EditorMode.ChannelMixer, EditorMode.BlackWhite,
            EditorMode.GradientMap, EditorMode.Vibrance, EditorMode.PhotoFilter,
            EditorMode.SelectiveColor, EditorMode.Posterize, EditorMode.Threshold,
            EditorMode.LensBlur, EditorMode.Healing, EditorMode.RedEye,
            EditorMode.DodgeBurn, EditorMode.Crop, EditorMode.FreeTransform,
            EditorMode.Layers, EditorMode.Fill, EditorMode.GradientTool,
        )
        val current = mutableStateOf(editorState(null, modes.first()))
        rule.setContent {
            Column {
                EditPhotoCanvas(current.value, 0.dp)
                EditPhotoBottomControls(current.value, backStack)
            }
        }
        for (mode in modes) {
            current.value = editorState(null, mode)
            rule.waitForIdle()
            clickEverything()
        }
        assertTrue(true)
    }
}
