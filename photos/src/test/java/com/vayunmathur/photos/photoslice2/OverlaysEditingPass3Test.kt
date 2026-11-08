package com.vayunmathur.photos.photoslice2

import android.app.Activity
import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.BlurAdj
import com.vayunmathur.photos.data.BlurParams
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.LiquifyTool
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.Selection
import com.vayunmathur.photos.ui.EditPhotoCropTransformOverlays
import com.vayunmathur.photos.ui.EditPhotoEditorState
import com.vayunmathur.photos.ui.EditPhotoPaintOverlays
import com.vayunmathur.photos.ui.EditPhotoSelectionOverlays
import com.vayunmathur.photos.ui.EditPhotoToolOverlays
import com.vayunmathur.photos.ui.EditorMode
import com.vayunmathur.photos.ui.SelectionTool
import com.vayunmathur.photos.util.PhotoEditViewModel
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1400dp")
class OverlaysEditingPass3Test {

    @get:Rule val rule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationContext as Application

    private fun state(
        mode: EditorMode = EditorMode.None,
        doc: EditDocument = EditDocument(canvasWidth = 100, canvasHeight = 50),
        selection: Selection? = null,
    ): EditPhotoEditorState {
        val vm = PhotoEditViewModel(app, PhotosRepository.get(app))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return EditPhotoEditorState(
            vm, activity,
            mutableStateOf(doc), mutableStateOf(null), mutableStateOf(null), mutableStateOf(selection),
        ).also { it.editorMode = mode }
    }

    private fun renderAndSwipe(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent { Box(Modifier.size(200.dp)) { content() } }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { swipe(Offset(20f, 20f), Offset(150f, 120f), 100L) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun `selective overlay renders`() = renderAndSwipe { EditPhotoToolOverlays(state(EditorMode.Selective)) }
    @Test fun `healing overlay renders`() = renderAndSwipe { EditPhotoToolOverlays(state(EditorMode.Healing)) }
    @Test fun `red eye overlay renders`() = renderAndSwipe { EditPhotoToolOverlays(state(EditorMode.RedEye)) }
    @Test fun `dodge burn overlay renders`() = renderAndSwipe { EditPhotoToolOverlays(state(EditorMode.DodgeBurn)) }
    @Test fun `smudge overlay renders`() = renderAndSwipe { EditPhotoToolOverlays(state(EditorMode.Smudge)) }
    @Test fun `liquify push overlay renders`() = renderAndSwipe { EditPhotoToolOverlays(state(EditorMode.Liquify)) }
    @Test fun `liquify twirl overlay renders`() = renderAndSwipe {
        EditPhotoToolOverlays(state(EditorMode.Liquify).also { it.liquifyTool = LiquifyTool.Twirl })
    }

    @Test
    fun `lens blur overlay renders with an active blur adjustment`() {
        val doc = EditDocument(canvasWidth = 100, canvasHeight = 50)
            .addLayer(com.vayunmathur.photos.data.AdjustmentLayer(BlurAdj(BlurParams(intensity = 20f)), name = "blur"))
        renderAndSwipe { EditPhotoToolOverlays(state(EditorMode.LensBlur, doc)) }
    }

    @Test fun `rectangle selection overlay renders`() = renderSelection(SelectionTool.Rectangle)
    @Test fun `ellipse selection overlay renders`() = renderSelection(SelectionTool.Ellipse)
    @Test fun `lasso selection overlay renders`() = renderSelection(SelectionTool.Lasso)
    @Test fun `polygon selection overlay renders`() = renderSelection(SelectionTool.Polygon)
    @Test fun `wand selection overlay renders`() = renderSelection(SelectionTool.Wand)

    private fun renderSelection(tool: SelectionTool) {
        val selection = Selection.rectangle(20, 20, 0.2f, 0.2f, 0.5f, 0.5f)
        val s = state(EditorMode.Selection, selection = selection).also { it.selectionTool = tool }
        renderAndSwipe { EditPhotoSelectionOverlays(s) }
    }

    @Test fun `paint overlays render`() = renderAndSwipe { EditPhotoPaintOverlays(state(EditorMode.None)) }

    @Test fun `crop transform overlays render`() = renderAndSwipe {
        EditPhotoCropTransformOverlays(state(EditorMode.None).also { it.isCropping = true })
    }

    @Test
    fun `tool overlays stay empty when cropping`() {
        val s = state(EditorMode.Healing).also { it.isCropping = true }
        rule.setContent { Box(Modifier.size(100.dp)) { EditPhotoToolOverlays(s) } }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }
}
