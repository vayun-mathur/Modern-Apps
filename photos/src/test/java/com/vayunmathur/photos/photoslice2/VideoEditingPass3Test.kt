package com.vayunmathur.photos.photoslice2

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.VideoEditState
import com.vayunmathur.photos.ui.CropOverlay
import com.vayunmathur.photos.ui.VideoEditPreview
import com.vayunmathur.photos.ui.VideoPlayer
import com.vayunmathur.photos.util.VideoEditViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1400dp")
class VideoEditingPass3Test {

    @get:Rule val rule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationContext as Application

    @Test
    fun `video player renders for a content uri`() {
        rule.setContent {
            Box(Modifier.size(320.dp, 200.dp)) {
                VideoPlayer(
                    modifier = Modifier,
                    uri = Uri.parse("content://fixture/clip.mp4"),
                    isMetadataVisible = false,
                    isSettledPage = false,
                )
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test
    fun `video player settled page renders with metadata`() {
        rule.setContent {
            Box(Modifier.size(320.dp, 200.dp)) {
                VideoPlayer(
                    modifier = Modifier,
                    uri = Uri.parse("content://fixture/clip2.mp4"),
                    isMetadataVisible = true,
                    isSettledPage = true,
                )
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test
    fun `video edit preview renders`() {
        val vm = VideoEditViewModel(app, PhotosRepository.get(app))
        rule.setContent {
            VideoEditPreview(
                vm = vm,
                state = VideoEditState(),
                uri = Uri.parse("content://fixture/clip.mp4"),
                showCropOverlay = false,
            )
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        assertNotNull(vm)
    }

    @Test
    fun `video crop overlay renders`() {
        rule.setContent {
            CropOverlay(
                state = VideoEditState(cropLeft = 0.1f, cropTop = 0.1f, cropRight = 0.9f, cropBottom = 0.9f),
                onCrop = {},
            )
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test
    fun `video edit state trim and crop defaults`() {
        val s = VideoEditState()
        assertEquals(0L, s.trimStartMs)
        assertEquals(null, s.cropLeft)
        assertEquals(0f, s.brightness)
    }
}
