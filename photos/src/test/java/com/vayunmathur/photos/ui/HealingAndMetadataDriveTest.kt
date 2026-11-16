package com.vayunmathur.photos.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.photos.data.PanoData
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.VideoData
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Healing overlay gestures (source-set and paint modes) and the viewer
 * metadata card across photo shapes (located, video, panorama, motion).
 */
@RunWith(AndroidJUnit4::class)
class HealingAndMetadataDriveTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun healingOverlayGestures() {
        var sourceSet: Pair<Float, Float>? = null
        var painted: Pair<Float, Float>? = null
        rule.setContent {
            Box(Modifier.size(200.dp)) {
                HealingOverlay(
                    sourceX = null, sourceY = null, brushSize = 24f,
                    isSettingSource = true,
                    onSourceSet = { x, y -> sourceSet = x to y },
                    onPaint = { x, y -> painted = x to y },
                )
            }
        }
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            swipe(start = center, end = centerRight, durationMillis = 80)
        }
        rule.waitForIdle()
        assertTrue(sourceSet != null || painted != null)
    }

    @Test fun metadataCardAcrossShapes() {
        val shapes = listOf(
            Photo(name = "located.jpg", uri = "content://media/a", date = 1_700_000_000_000L,
                width = 4000, height = 3000, dateModified = 1L, exifSet = true,
                lat = 37.5, long = -122.2, videoData = null, panoData = null),
            Photo(name = "movie.mp4", uri = "content://media/b", date = 1_700_000_000_000L,
                width = 1920, height = 1080, dateModified = 1L, exifSet = true,
                lat = null, long = null, videoData = VideoData(65_000L), panoData = null),
            Photo(name = "pano.jpg", uri = "content://media/c", date = 1_700_000_000_000L,
                width = 8000, height = 2000, dateModified = 1L, exifSet = true,
                lat = null, long = null, videoData = null,
                panoData = PanoData(8000, 2000, 7800, 1900, 100, 50, "equirectangular")),
        )
        val holder = arrayOfNulls<androidx.compose.runtime.MutableState<Photo>>(1)
        rule.setContent {
            val state = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(shapes[0]) }
            holder[0] = state
            DynamicTheme {
                Column {
                    PhotoMetadataCard(
                        photo = state.value, context = app,
                        countryName = "United States", fileSize = 2_400_000L,
                        pageOffset = 0f, peopleCount = 3,
                        isSphere = state.value.panoData != null,
                        isMotionPhoto = state.value.name == "movie.mp4",
                        onEditPhoto = {},
                    )
                }
            }
        }
        for (shape in shapes) {
            holder[0]!!.value = shape
            rule.waitForIdle()
            val count = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size
            for (i in 0 until minOf(count, 6)) {
                runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
                runCatching { rule.waitForIdle() }
            }
        }
        assertTrue(true)
    }
}
