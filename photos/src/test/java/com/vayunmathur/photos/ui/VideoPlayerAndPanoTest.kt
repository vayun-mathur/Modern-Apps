package com.vayunmathur.photos.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.photos.data.PanoData
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.platform.workInfosOverride
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The viewer's video player (ExoPlayer under Robolectric) and the
 * flat panorama strip: both compose and respond to their chrome.
 */
@RunWith(AndroidJUnit4::class)
class VideoPlayerAndPanoTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setup() {
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        TestPhotosRepository.install(app)
    }

    @Test fun videoPlayerComposesAndToggles() {
        rule.setContent {
            DynamicTheme {
                Box(Modifier.size(320.dp, 200.dp)) {
                    VideoPlayer(
                        Modifier.size(320.dp, 200.dp),
                        android.net.Uri.parse("content://media/movie.mp4"),
                        isMetadataVisible = true,
                        isSettledPage = true,
                    )
                }
            }
        }
        rule.waitForIdle()
        val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
        for (i in 0 until minOf(count, 6)) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            runCatching { rule.waitForIdle() }
        }
    }

    @Test fun panoramaFlatViewScrolls() {
        val photo = Photo(
            name = "pano.jpg", uri = "content://media/pano", date = 1L,
            width = 4000, height = 1000, dateModified = 1L, exifSet = true,
            lat = null, long = null, videoData = null,
            panoData = PanoData(4000, 1000, 3800, 900, 100, 50, "equirectangular"),
        )
        rule.setContent {
            DynamicTheme {
                Box(Modifier.size(300.dp, 120.dp)) { PanoramaFlatView(photo) }
            }
        }
        rule.waitForIdle()
    }

}
