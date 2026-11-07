package com.vayunmathur.contacts.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import java.io.File
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Crop-rect handle drags. The screen is placed in a 360x640 box; Robolectric
 * runs at density 1, so the square image displays at 360px, the crop square
 * is 288px, and its initial bounds are (36, 208)..(324, 496) in box coords.
 */
@RunWith(RobolectricTestRunner::class)
class CropHandlesPass2Test {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun renderCrop(): () -> Bitmap? {
        val bitmapFile = File(app.cacheDir, "crop-handles.jpg")
        Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
            bitmapFile.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        var cropped: Bitmap? = null
        rule.setContent {
            DynamicTheme {
                Box(Modifier.requiredSize(360.dp, 640.dp).testTag("cropRoot")) {
                    CropPhotoScreen(
                        uri = Uri.fromFile(bitmapFile).toString(),
                        onCropComplete = { cropped = it },
                        onCancel = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onNodeWithTag("cropRoot").captureToImage().width > 0)
        return { cropped }
    }

    private fun drag(start: Offset, end: Offset) {
        rule.onNodeWithTag("cropRoot").performTouchInput {
            swipe(start, end, 250)
        }
        rule.waitForIdle()
    }

    private fun finishCrop(cropped: () -> Bitmap?) {
        val done = app.getString(com.vayunmathur.library.ui.R.string.done)
        rule.onNodeWithText(done, substring = true).performClick()
        rule.waitForIdle()
        val result = assertNotNull(cropped(), "Done should deliver a cropped bitmap")
        assertTrue(result.width == 1024 && result.height == 1024)
    }

    @Test fun topLeftHandleDragResizes() {
        val cropped = renderCrop()
        drag(Offset(36f, 208f), Offset(66f, 238f))
        finishCrop(cropped)
    }

    @Test fun topRightHandleDragResizes() {
        val cropped = renderCrop()
        drag(Offset(324f, 208f), Offset(294f, 238f))
        finishCrop(cropped)
    }

    @Test fun bottomLeftHandleDragResizes() {
        val cropped = renderCrop()
        drag(Offset(36f, 496f), Offset(66f, 466f))
        finishCrop(cropped)
    }

    @Test fun bottomRightHandleDragResizes() {
        val cropped = renderCrop()
        drag(Offset(324f, 496f), Offset(294f, 466f))
        finishCrop(cropped)
    }

    @Test fun topHandleDragResizes() {
        val cropped = renderCrop()
        drag(Offset(180f, 208f), Offset(180f, 238f))
        finishCrop(cropped)
    }

    @Test fun bottomHandleDragResizes() {
        val cropped = renderCrop()
        drag(Offset(180f, 496f), Offset(180f, 466f))
        finishCrop(cropped)
    }

    @Test fun leftHandleDragResizes() {
        val cropped = renderCrop()
        drag(Offset(36f, 352f), Offset(66f, 352f))
        finishCrop(cropped)
    }

    @Test fun rightHandleDragResizes() {
        val cropped = renderCrop()
        drag(Offset(324f, 352f), Offset(294f, 352f))
        finishCrop(cropped)
    }

    @Test fun moveAndOutsideDrags() {
        val cropped = renderCrop()
        drag(Offset(180f, 352f), Offset(200f, 362f))
        // outside the image/crop rect: no handle and no move
        drag(Offset(10f, 90f), Offset(40f, 90f))
        finishCrop(cropped)
    }
}
