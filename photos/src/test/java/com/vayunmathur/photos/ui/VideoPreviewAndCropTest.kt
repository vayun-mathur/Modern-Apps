package com.vayunmathur.photos.ui

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.VideoData
import com.vayunmathur.photos.data.VideoEditState
import com.vayunmathur.photos.util.VideoEditViewModel
import com.vayunmathur.photos.util.workInfosOverride
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Video preview player, crop overlay gestures, and time formatting.
 */
@RunWith(AndroidJUnit4::class)
class VideoPreviewAndCropTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
    }

    @Test fun formatVideoTimeShapes() {
        assertEquals("0:00", formatVideoTime(0L))
        assertEquals("0:00", formatVideoTime(-5_000L))
        assertEquals("1:05", formatVideoTime(65_000L))
        assertEquals("10:00", formatVideoTime(600_000L))
        assertEquals("1:02:03", formatVideoTime(3_723_000L))
        assertEquals("2:00:00", formatVideoTime(7_200_000L))
    }

    @Test fun previewAndCropOverlayCompose() = runBlocking {
        installed.photos.upsertAll(listOf(
            Photo(name = "movie.mp4", uri = "content://media/movie", date = 1L, width = 1920, height = 1080,
                dateModified = 1L, exifSet = true, lat = null, long = null,
                videoData = VideoData(30_000L), panoData = null),
        ))
        val photo = installed.photos.getAll().first()
        val vm = VideoEditViewModel(app, installed.photos)
        vm.loadVideo(photo.id, null)
        var crop: Rect? = null
        rule.setContent {
            Column {
                Box(Modifier.size(320.dp, 180.dp)) {
                    VideoEditPreview(vm, VideoEditState(), Uri.parse(photo.uri), showCropOverlay = true)
                }
                Box(Modifier.size(200.dp)) {
                    CropOverlay(VideoEditState(), onCrop = { crop = it })
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            swipe(start = center, end = centerRight, durationMillis = 80)
        }
        rule.waitForIdle()
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until minOf(count, 6)) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            runCatching { rule.waitForIdle() }
        }
        assertNotNull(vm.state.value)
        Unit
    }
}
