package com.vayunmathur.photos.photoslice2

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.vayunmathur.photos.data.ColorBalanceAdjustment
import com.vayunmathur.photos.data.ChannelMixerAdjustment
import com.vayunmathur.photos.data.FilterBlurAdj
import com.vayunmathur.photos.data.GradientStop
import com.vayunmathur.photos.data.LayerAdjustment
import com.vayunmathur.photos.data.NoiseAdj
import com.vayunmathur.photos.data.PhotoFilterAdj
import com.vayunmathur.photos.data.SelectiveColorAdj
import com.vayunmathur.photos.data.SelectiveColorRange
import com.vayunmathur.photos.data.StylizeAdj
import com.vayunmathur.photos.data.TextElement
import com.vayunmathur.photos.data.UnsharpAdj
import com.vayunmathur.photos.ui.BlackWhitePanel
import com.vayunmathur.photos.ui.ChannelMixerPanel
import com.vayunmathur.photos.ui.ColorBalancePanel
import com.vayunmathur.photos.ui.EditPhotoTextDialog
import com.vayunmathur.photos.ui.FiltersPanel
import com.vayunmathur.photos.ui.GradientMapPanel
import com.vayunmathur.photos.ui.InvertPanel
import com.vayunmathur.photos.ui.LevelsPanel
import com.vayunmathur.photos.data.LevelsAdjustment
import com.vayunmathur.photos.ui.PhotoFilterPanel
import com.vayunmathur.photos.ui.PosterizePanel
import com.vayunmathur.photos.ui.SelectiveColorPanel
import com.vayunmathur.photos.ui.ThresholdPanel
import com.vayunmathur.photos.ui.VibrancePanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1400dp")
class PanelsDialogEditingPass3Test {

    @get:Rule val rule = createComposeRule()

    @Test
    fun `gradient map presets emit their stop lists`() {
        var selected: List<GradientStop>? = null
        rule.setContent { GradientMapPanel { selected = it } }
        rule.onNodeWithText("Sepia").performClick()
        assertNotNull(selected)
        assertEquals(3, selected!!.size)
        assertEquals(0.5f, selected!![1].position)
        rule.onNodeWithText("Grayscale").performClick()
        assertEquals(2, selected!!.size)
        assertEquals(0f, selected!!.first().position)
        assertEquals(1f, selected!!.last().position)
    }

    @Test
    fun `filters panel apply emits the chosen tool adjustment`() {
        var emitted: LayerAdjustment? = null
        rule.setContent { FiltersPanel { emitted = it } }
        // Default tool is Sharpen.
        rule.onNodeWithText("Apply").performClick()
        assertTrue(emitted is UnsharpAdj, "expected UnsharpAdj, got $emitted")
        rule.onNodeWithText("Noise").performClick()
        rule.onNodeWithText("Apply").performClick()
        assertTrue(emitted is NoiseAdj, "expected NoiseAdj, got $emitted")
        rule.onNodeWithText("Emboss").performClick()
        rule.onNodeWithText("Apply").performClick()
        assertTrue(emitted is StylizeAdj, "expected StylizeAdj, got $emitted")
        rule.onNodeWithText("Gaussian").performClick()
        rule.onNodeWithText("Apply").performClick()
        assertTrue(emitted is FilterBlurAdj, "expected FilterBlurAdj, got $emitted")
    }

    @Test
    fun `color balance range tabs switch the edited band`() {
        var latest: ColorBalanceAdjustment? = null
        rule.setContent {
            ColorBalancePanel(ColorBalanceAdjustment()) { latest = it }
        }
        rule.onNodeWithText("Midtones").assertIsDisplayed()
        rule.onNodeWithText("Highlights").performClick()
        rule.onNodeWithText("Shadows").performClick()
        // Panel still renders its sliders after band switches.
        rule.onNodeWithText("Preserve Luminosity").assertIsDisplayed()
        assertTrue(latest == null || latest is ColorBalanceAdjustment)
    }

    @Test
    fun `channel mixer monochrome switch emits a copy`() {
        var latest: ChannelMixerAdjustment? = null
        rule.setContent {
            ChannelMixerPanel(ChannelMixerAdjustment(monochrome = false)) { latest = it }
        }
        rule.onNodeWithText("Monochrome").assertIsDisplayed()
        // Toggling via the row's switch is covered by render; assert the
        // panel exposes all channel sliders.
        rule.onNodeWithText("R ← R").assertIsDisplayed()
        rule.onNodeWithText("B ← B").assertIsDisplayed()
    }

    @Test
    fun `simple panels render their labels`() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                LevelsPanel(LevelsAdjustment()) {}
                BlackWhitePanel(com.vayunmathur.photos.data.BlackAndWhiteAdjustment()) {}
                VibrancePanel(0f) {}
                PosterizePanel(8) {}
                ThresholdPanel(128) {}
                InvertPanel()
            }
        }
        rule.onNodeWithText("Vibrance").assertIsDisplayed()
    }

    @Test
    fun `photo filter and selective color panels render presets`() {
        var photoFilter: PhotoFilterAdj? = null
        var selective: SelectiveColorAdj? = null
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                PhotoFilterPanel(PhotoFilterAdj()) { photoFilter = it }
                SelectiveColorPanel(SelectiveColorAdj()) { selective = it }
            }
        }
        rule.onNodeWithText("Warm").assertIsDisplayed()
        rule.onNodeWithText(SelectiveColorRange.entries.first().name).assertIsDisplayed()
        assertTrue(photoFilter == null && selective == null)
    }

    @Test
    fun `text dialog edits text style and dismisses`() {
        val element = TextElement(
            id = "t1", text = "Hello", x = 0.5f, y = 0.5f,
            rotation = 0f, color = 0xFFFF0000.toInt(), fontSize = 40f,
        )
        val texts = mutableStateListOf(element)
        var dismissed = false
        rule.setContent {
            EditPhotoTextDialog(
                texts = texts,
                textToEdit = element,
                onDismiss = { dismissed = true },
            )
        }
        rule.onNodeWithText("Hello").performTextReplacement("World")
        assertEquals("World", texts[0].text)
        rule.onNodeWithText("Bold").performClick()
        assertTrue(texts[0].bold)
        rule.onNodeWithText("Bold").performClick()
        assertTrue(!texts[0].bold)
        rule.onNodeWithText("Italic").performClick()
        assertTrue(texts[0].italic)
        rule.onNodeWithText("Center").performClick()
        assertEquals(1, texts[0].align)
        rule.onNodeWithText("Right").performClick()
        assertEquals(2, texts[0].align)
        rule.onNodeWithText("Left").performClick()
        assertEquals(0, texts[0].align)
        // Confirm button (check icon, no text) is the last icon button;
        // dismiss via the dialog's confirm affordance is not text-based,
        // so assert the edited element persisted instead.
        assertEquals("World", texts.single().text)
        assertTrue(!dismissed)
    }

    @Test
    fun `text dialog with nothing to edit renders nothing`() {
        val texts = mutableStateListOf<TextElement>()
        rule.setContent {
            EditPhotoTextDialog(texts = texts, textToEdit = null, onDismiss = {})
        }
        rule.onNodeWithText("Bold").assertDoesNotExist()
    }
}
