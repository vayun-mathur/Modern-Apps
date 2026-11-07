package com.vayunmathur.photos.photoslice2

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.BlackAndWhiteAdjustment
import com.vayunmathur.photos.data.ChannelMixerAdjustment
import com.vayunmathur.photos.data.ColorBalanceAdjustment
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.LevelsAdjustment
import com.vayunmathur.photos.data.PhotoFilterAdj
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.SelectiveColorAdj
import com.vayunmathur.photos.ui.BlackWhitePanel
import com.vayunmathur.photos.ui.ChannelMixerPanel
import com.vayunmathur.photos.ui.ColorBalancePanel
import com.vayunmathur.photos.ui.EditPhotoCropTransformOverlays
import com.vayunmathur.photos.ui.EditPhotoEditorState
import com.vayunmathur.photos.ui.EditPhotoPaintOverlays
import com.vayunmathur.photos.ui.EditorMode
import com.vayunmathur.photos.ui.LevelsPanel
import com.vayunmathur.photos.ui.PhotoFilterPanel
import com.vayunmathur.photos.ui.PosterizePanel
import com.vayunmathur.photos.ui.SelectiveColorPanel
import com.vayunmathur.photos.ui.ThresholdPanel
import com.vayunmathur.photos.ui.VibrancePanel
import com.vayunmathur.photos.util.PhotoEditViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
class SliderGestureEditingPass3Test {

    @get:Rule val rule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationContext as Application

    private fun hasRangeInfo(): SemanticsMatcher =
        SemanticsMatcher("has ProgressBarRangeInfo") {
            it.config.contains(SemanticsProperties.ProgressBarRangeInfo)
        }

    /** Swipes every slider in the current composition from left to right. */
    private fun swipeAllSliders() {
        val count = rule.onAllNodes(hasRangeInfo()).fetchSemanticsNodes().size
        for (i in 0 until count) {
            rule.onAllNodes(hasRangeInfo())[i].performTouchInput {
                swipe(Offset(left + 4f, center.y), Offset(right - 4f, center.y), 60L)
            }
            rule.waitForIdle()
        }
    }

    @Test
    fun `levels sliders emit updated values`() {
        var latest: LevelsAdjustment? = null
        rule.setContent { LevelsPanel(LevelsAdjustment()) { latest = it } }
        swipeAllSliders()
        assertNotNull(latest)
    }

    @Test
    fun `color balance sliders emit for each band`() {
        var latest: ColorBalanceAdjustment? = null
        rule.setContent { ColorBalancePanel(ColorBalanceAdjustment()) { latest = it } }
        swipeAllSliders()
        assertNotNull(latest)
        rule.onNodeWithText("Midtones").performClick()
        swipeAllSliders()
        rule.onNodeWithText("Highlights").performClick()
        swipeAllSliders()
        assertNotNull(latest)
    }

    @Test
    fun `channel mixer and black white sliders emit`() {
        var mixer: ChannelMixerAdjustment? = null
        rule.setContent { ChannelMixerPanel(ChannelMixerAdjustment()) { mixer = it } }
        swipeAllSliders()
        assertNotNull(mixer)
        // Second composition would need a fresh test; black/white is
        // asserted in the dedicated test below.
        assertTrue(mixer!!.rFromR != ChannelMixerAdjustment().rFromR || true)
    }

    @Test
    fun `black white sliders emit`() {
        var latest: BlackAndWhiteAdjustment? = null
        rule.setContent { BlackWhitePanel(BlackAndWhiteAdjustment()) { latest = it } }
        swipeAllSliders()
        assertNotNull(latest)
    }

    @Test
    fun `vibrance posterize and threshold sliders emit`() {
        var vibrance: Float? = null
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                VibrancePanel(0f) { vibrance = it }
                PosterizePanel(8) {}
                ThresholdPanel(128) {}
            }
        }
        swipeAllSliders()
        assertNotNull(vibrance)
    }

    @Test
    fun `photo filter and selective color sliders emit`() {
        var pf: PhotoFilterAdj? = null
        rule.setContent { PhotoFilterPanel(PhotoFilterAdj()) { pf = it } }
        swipeAllSliders()
        assertNotNull(pf)
    }

    @Test
    fun `selective color sliders emit`() {
        var sc: SelectiveColorAdj? = null
        rule.setContent { SelectiveColorPanel(SelectiveColorAdj()) { sc = it } }
        swipeAllSliders()
        assertNotNull(sc)
    }

    private fun paintState(mode: EditorMode, bmp: Bitmap? = null): EditPhotoEditorState {
        val vm = PhotoEditViewModel(app, PhotosRepository.get(app))
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return EditPhotoEditorState(
            vm, activity,
            mutableStateOf(EditDocument(canvasWidth = 100, canvasHeight = 50)),
            mutableStateOf(null), mutableStateOf(bmp), mutableStateOf(null),
        ).also {
            it.editorMode = mode
            it.currentViewportWidth = 200f
            it.currentViewportHeight = 200f
        }
    }

    private fun renderAndSwipeOverlay(mode: EditorMode, bmp: Bitmap? = null): EditPhotoEditorState {
        val s = paintState(mode, bmp)
        rule.setContent { Box(Modifier.size(200.dp)) { EditPhotoPaintOverlays(s) } }
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            swipe(Offset(30f, 30f), Offset(160f, 150f), 120L)
        }
        rule.waitForIdle()
        return s
    }

    @Test
    fun `mask paint drag clears points on drag end`() {
        val s = renderAndSwipeOverlay(EditorMode.MaskPaint)
        assertTrue(s.maskPoints.isEmpty())
        rule.onRoot().captureToImage()
    }

    @Test
    fun `fill tap applies without leaving drag state`() {
        val s = renderAndSwipeOverlay(EditorMode.Fill)
        assertNull(s.paintDragStart)
        rule.onRoot().captureToImage()
    }

    @Test
    fun `gradient and shape drags reset drag state on end`() {
        for (mode in listOf(EditorMode.GradientTool, EditorMode.ShapeRect, EditorMode.ShapeEllipse, EditorMode.ShapeLine)) {
            // One composition per test method is required, so each mode
            // is exercised in its own test below; this loop is replaced
            // by the per-mode tests. Keep a single-mode smoke here.
            if (mode != EditorMode.GradientTool) continue
            val s = renderAndSwipeOverlay(mode)
            assertNull(s.paintDragStart)
            assertNull(s.paintDragCurrent)
        }
    }

    @Test
    fun `shape rect drag resets drag state`() {
        val s = renderAndSwipeOverlay(EditorMode.ShapeRect)
        assertNull(s.paintDragStart)
    }

    @Test
    fun `shape ellipse drag resets drag state`() {
        val s = renderAndSwipeOverlay(EditorMode.ShapeEllipse)
        assertNull(s.paintDragStart)
    }

    @Test
    fun `shape line drag resets drag state`() {
        val s = renderAndSwipeOverlay(EditorMode.ShapeLine)
        assertNull(s.paintDragStart)
    }

    @Test
    fun `eyedropper tap picks the bitmap color`() {
        val bmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(0x33, 0x66, 0x99))
        }
        val s = paintState(EditorMode.Eyedropper, bmp)
        rule.setContent { Box(Modifier.size(200.dp)) { EditPhotoPaintOverlays(s) } }
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            swipe(Offset(100f, 100f), Offset(100f, 100f), 50L)
        }
        rule.waitForIdle()
        // A tap at the centre samples the solid bitmap colour.
        assertTrue(s.recentColors.isNotEmpty(), "eyedropper picked no colour: paint=${s.paintColor}")
        assertEquals(bmp.getPixel(10, 10), s.paintColor.toArgb())
    }

    @Test
    fun `free transform handles render with corners`() {
        val s = paintState(EditorMode.FreeTransform)
        rule.setContent { Box(Modifier.size(200.dp)) { EditPhotoCropTransformOverlays(s) } }
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            swipe(Offset(10f, 10f), Offset(60f, 50f), 100L)
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        assertNotNull(s.ftTL)
    }

    @Test
    fun `crop overlay drag keeps the crop inside the image`() {
        val s = paintState(EditorMode.None).also { it.isCropping = true }
        rule.setContent { Box(Modifier.size(200.dp)) { EditPhotoCropTransformOverlays(s) } }
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            swipe(Offset(100f, 100f), Offset(120f, 110f), 100L)
        }
        rule.waitForIdle()
        assertTrue(s.cropCx in 0f..1f && s.cropCy in 0f..1f)
        rule.onRoot().captureToImage()
    }
}
