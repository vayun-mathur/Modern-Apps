package com.vayunmathur.photos.photoslice2

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.CurveChannel
import com.vayunmathur.photos.data.CurvesAdjustment
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.ui.CurvesPanel
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
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1400dp")
class CurvesAndPanelsEditingPass3Test {

    @get:Rule val rule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationContext as Application

    @Test
    fun `curves panel channel switch and canvas drag add points`() {
        var curves = CurvesAdjustment()
        var channel: CurveChannel = CurveChannel.Combined
        rule.setContent {
            CurvesPanel(
                curves = curves,
                selectedChannel = channel,
                onChannelSelected = { channel = it },
                onCurvesChanged = { curves = it },
            )
        }
        rule.onNodeWithText("Red").performClick()
        assertEquals(CurveChannel.Red, channel)
        rule.onNodeWithText("Blue").performClick()
        assertEquals(CurveChannel.Blue, channel)
        // Dragging on the canvas adds/moves a control point on the channel.
        rule.onRoot().performTouchInput {
            swipe(Offset(100f, 300f), Offset(200f, 150f), 120L)
        }
        rule.waitForIdle()
        assertTrue(curves.blue.size >= 2, "blue channel gained a point: ${curves.blue.size}")
        rule.onRoot().captureToImage()
    }

    @Test
    fun `curves panel renders all channels`() {
        var channel: CurveChannel = CurveChannel.Combined
        rule.setContent {
            CurvesPanel(CurvesAdjustment(), channel, { channel = it }, {})
        }
        for (name in listOf("Combined", "Red", "Green", "Blue")) {
            rule.onNodeWithText(name).assertExists()
        }
        rule.onRoot().captureToImage()
        assertNotNull(channel)
    }

    private fun state(category: ToolCategory, mode: EditorMode): EditPhotoEditorState {
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

    private fun renderMode(category: ToolCategory, mode: EditorMode) {
        val s = state(category, mode)
        val backStack = NavBackStack<EditRoute>(arrayOf(EditRoute.EditPhoto(0L)))
        rule.setContent { EditPhotoBottomControls(s, backStack) }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        assertEquals(mode, s.editorMode)
    }

    @Test fun `panel host curves mode`() = renderMode(ToolCategory.Adjust, EditorMode.Curves)
    @Test fun `panel host hsl mode`() = renderMode(ToolCategory.Adjust, EditorMode.HSL)
    @Test fun `panel host levels mode`() = renderMode(ToolCategory.Adjust, EditorMode.Levels)
    @Test fun `panel host black white mode`() = renderMode(ToolCategory.Adjust, EditorMode.BlackWhite)
    @Test fun `panel host lens blur mode`() = renderMode(ToolCategory.Filters, EditorMode.LensBlur)
    @Test fun `panel host selective mode`() = renderMode(ToolCategory.Filters, EditorMode.Selective)
    @Test fun `panel host healing mode`() = renderMode(ToolCategory.Retouch, EditorMode.Healing)
    @Test fun `panel host selection mode`() = renderMode(ToolCategory.Select, EditorMode.Selection)
    @Test fun `panel host free transform mode`() = renderMode(ToolCategory.Transform, EditorMode.FreeTransform)
    @Test fun `panel host layers mode`() = renderMode(ToolCategory.Layers, EditorMode.Layers)

    @Test fun `panel host color balance mode`() = renderMode(ToolCategory.Adjust, EditorMode.ColorBalance)
    @Test fun `panel host channel mixer mode`() = renderMode(ToolCategory.Adjust, EditorMode.ChannelMixer)
    @Test fun `panel host gradient map mode`() = renderMode(ToolCategory.Adjust, EditorMode.GradientMap)
    @Test fun `panel host vibrance mode`() = renderMode(ToolCategory.Adjust, EditorMode.Vibrance)
    @Test fun `panel host photo filter mode`() = renderMode(ToolCategory.Adjust, EditorMode.PhotoFilter)
    @Test fun `panel host selective color mode`() = renderMode(ToolCategory.Adjust, EditorMode.SelectiveColor)
    @Test fun `panel host posterize mode`() = renderMode(ToolCategory.Adjust, EditorMode.Posterize)
    @Test fun `panel host threshold mode`() = renderMode(ToolCategory.Adjust, EditorMode.Threshold)
    @Test fun `panel host invert mode`() = renderMode(ToolCategory.Adjust, EditorMode.Invert)
    @Test fun `panel host filter fx mode`() = renderMode(ToolCategory.Filters, EditorMode.FilterFx)
    @Test fun `panel host liquify mode`() = renderMode(ToolCategory.Filters, EditorMode.Liquify)
    @Test fun `panel host red eye mode`() = renderMode(ToolCategory.Retouch, EditorMode.RedEye)
    @Test fun `panel host dodge burn mode`() = renderMode(ToolCategory.Retouch, EditorMode.DodgeBurn)
    @Test fun `panel host smudge mode`() = renderMode(ToolCategory.Retouch, EditorMode.Smudge)
    @Test fun `panel host mask paint mode`() = renderMode(ToolCategory.Layers, EditorMode.MaskPaint)
    @Test fun `panel host fill mode`() = renderMode(ToolCategory.Paint, EditorMode.Fill)
    @Test fun `panel host gradient tool mode`() = renderMode(ToolCategory.Paint, EditorMode.GradientTool)
    @Test fun `panel host shape rect mode`() = renderMode(ToolCategory.Paint, EditorMode.ShapeRect)
    @Test fun `panel host eyedropper mode`() = renderMode(ToolCategory.Paint, EditorMode.Eyedropper)
}
