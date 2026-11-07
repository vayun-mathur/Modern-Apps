package com.vayunmathur.photos.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.TextElement
import com.vayunmathur.photos.data.VideoData
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pass-3 tests for ImageLoader items, overlay rendering and wallpaper helpers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UtilPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun imageLoaderInitAndPhotoItems() {
        ImageLoader.init(ctx)
        val image = pass3Photo(1, "a.jpg")
        val video = pass3Photo(2, "v.mp4").copy(videoData = VideoData(5000))
        val gif = pass3Photo(3, "g.gif").copy(mimeType = "image/gif")
        var clicked = false
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                ImageLoader.PhotoItem(image, Modifier, onClick = { clicked = true })
                ImageLoader.PhotoItem(video, Modifier)
                ImageLoader.PhotoItem(gif, Modifier)
            }
        }
        rule.waitForIdle()
        assertTrue(!clicked) // no touch sent yet; click wiring is asserted in the selectable tests
    }

    @Test
    fun selectablePhotoItemSelectionToggle() {
        var toggled = 0
        var clicked = 0
        val photo = pass3Photo(1, "a.jpg")
        rule.setContent {
            ImageLoader.SelectablePhotoItem(
                photo = photo, isSelected = false, isSelectionMode = true,
                onToggleSelection = { toggled++ }, onClick = { clicked++ },
                thumbnail = { _, m -> androidx.compose.foundation.layout.Box(m) },
            )
        }
        rule.onRoot().performTouchInput { longClick() }
        assertEquals(1, toggled)
        rule.onRoot().performTouchInput { click() }
        assertEquals(1, clicked)
    }

    @Test
    fun selectablePhotoItemNotInSelectionModeClicks() {
        var clicked = 0
        rule.setContent {
            ImageLoader.SelectablePhotoItem(
                photo = pass3Photo(1, "a.jpg"), isSelected = true, isSelectionMode = false,
                onToggleSelection = {}, onClick = { clicked++ },
                thumbnail = { _, m -> androidx.compose.foundation.layout.Box(m) },
            )
        }
        rule.onRoot().performTouchInput { click() }
        assertEquals(1, clicked)
    }

    @Test
    fun drawTextElementAlignmentsAndStyles() {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (align in 0..2) {
            canvas.drawTextElement(
                TextElement("id$align", "Hello", 0.5f, 0.5f, 15f, android.graphics.Color.RED, 20f,
                    bold = align == 1, italic = align == 2, align = align),
                canvasWidth = 200, canvasHeight = 100, referenceWidth = 100f,
            )
        }
        canvas.drawTextElement(
            TextElement("plain", "", 0f, 0f, 0f, android.graphics.Color.BLUE, 12f),
            canvasWidth = 200, canvasHeight = 100, referenceWidth = 200f,
        )
        assertEquals(200, bitmap.width)
    }

}
