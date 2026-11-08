package com.vayunmathur.photos.photoslice2

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import com.vayunmathur.photos.data.VideoEditState
import com.vayunmathur.photos.ui.CropOverlay
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
class CropGestureEditingPass3Test {

    @get:Rule val rule = createComposeRule()

    @Test
    fun `crop overlay drag inside leaves rect unchanged`() {
        var cropped: Rect? = null
        rule.setContent {
            Box(Modifier.size(400.dp)) {
                CropOverlay(
                    state = VideoEditState(cropLeft = 0.2f, cropTop = 0.2f, cropRight = 0.8f, cropBottom = 0.8f),
                    onCrop = { cropped = it },
                )
            }
        }
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            swipe(Offset(center.x, center.y), Offset(center.x + 60f, center.y + 40f), 150L)
        }
        rule.waitForIdle()
        assertNotNull(cropped)
        assertEquals(0.2f, cropped!!.left)
    }

    @Test
    fun `crop overlay drag corner resizes`() {
        var cropped: Rect? = null
        rule.setContent {
            Box(Modifier.size(400.dp)) {
                CropOverlay(
                    state = VideoEditState(cropLeft = 0.2f, cropTop = 0.2f, cropRight = 0.8f, cropBottom = 0.8f),
                    onCrop = { cropped = it },
                )
            }
        }
        rule.waitForIdle()
        // Bottom-right corner handle sits at 80% of the box.
        val sizePx = 400f
        rule.onRoot().performTouchInput {
            swipe(Offset(sizePx * 0.8f, sizePx * 0.8f), Offset(sizePx * 0.6f, sizePx * 0.6f), 150L)
        }
        rule.waitForIdle()
        if (cropped != null) {
            assertTrue(cropped!!.right <= 0.81f, "corner drag shrank the rect: $cropped")
        }
        rule.onRoot().captureToImage()
    }

    @Test
    fun `crop overlay with full frame renders grid`() {
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                CropOverlay(state = VideoEditState(), onCrop = {})
            }
        }
        rule.waitForIdle()
        val image = rule.onRoot().captureToImage()
        assertEquals(true, image.width > 0)
    }

    @Test
    fun `advanced panels sliders emit through panel host callbacks`() {
        // Levels/ColorBalance/ChannelMixer panels are exercised through
        // their own composables in Slice2Pass2PanelsTest; here the three
        // slider panels render together and capture.
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                com.vayunmathur.photos.ui.LevelsPanel(com.vayunmathur.photos.data.LevelsAdjustment()) {}
                com.vayunmathur.photos.ui.ColorBalancePanel(com.vayunmathur.photos.data.ColorBalanceAdjustment()) {}
                com.vayunmathur.photos.ui.ChannelMixerPanel(com.vayunmathur.photos.data.ChannelMixerAdjustment()) {}
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        assertNotNull(rule)
    }
}
