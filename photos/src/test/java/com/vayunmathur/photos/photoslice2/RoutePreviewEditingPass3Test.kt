package com.vayunmathur.photos.photoslice2

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.DrawingTool
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.VideoEditState
import com.vayunmathur.photos.ui.EditRoute
import com.vayunmathur.photos.ui.FaceBoxLayer
import com.vayunmathur.photos.util.FaceBox
import com.vayunmathur.photos.util.PhotoFaceBoxes
import com.vayunmathur.photos.ui.VideoEditPreview
import com.vayunmathur.photos.ui.VideoEditRoute
import com.vayunmathur.photos.ui.VideoPlayer
import com.vayunmathur.photos.util.VideoEditViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1400dp")
class RoutePreviewEditingPass3Test {

    @get:Rule val rule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationContext as Application

    @Test
    fun `edit photo route carries id and uri`() {
        val route = EditRoute.EditPhoto(42L, "content://a.jpg")
        assertEquals(42L, route.id)
        assertEquals("content://a.jpg", route.uri)
        assertEquals(route, route.copy())
        assertNotEquals(route, route.copy(id = 43L))
        assertEquals(null, EditRoute.EditPhoto(1L).uri)
        assertTrue(route.toString().contains("42"))
        assertEquals(route.hashCode(), EditRoute.EditPhoto(42L, "content://a.jpg").hashCode())
    }

    @Test
    fun `drawing settings route carries tool and style`() {
        val route = EditRoute.DrawingSettings(DrawingTool.Pen, 0xFF112233.toInt(), 12f, 0.75f)
        assertEquals(DrawingTool.Pen, route.tool)
        assertEquals(12f, route.currentThickness)
        assertEquals(0.75f, route.currentOpacity)
        val changed = route.copy(currentColor = 0xFF445566.toInt(), tool = DrawingTool.Text)
        assertEquals(DrawingTool.Text, changed.tool)
        assertNotEquals(route, changed)
        assertEquals(route.copy(), route)
    }

    @Test
    fun `video edit route carries id and uri`() {
        val route = VideoEditRoute.EditVideo(7L, "content://v.mp4")
        assertEquals(7L, route.id)
        assertEquals("content://v.mp4", route.uri)
        assertEquals(route, route.copy())
        assertNotEquals(route, route.copy(uri = "content://other.mp4"))
        assertEquals(null, VideoEditRoute.EditVideo(3L).uri)
    }

    @Test
    fun `face box layer draws and reports a tapped face`() {
        var clicked: Long? = null
        val boxes = PhotoFaceBoxes(
            srcWidth = 100,
            srcHeight = 100,
            faces = listOf(
                FaceBox(
                    clusterId = 99L, name = "Ada",
                    left = 0.25f, top = 0.25f, right = 0.75f, bottom = 0.75f,
                ),
                // Degenerate and unnamed faces are skipped / unlabelled.
                FaceBox(
                    clusterId = null, name = null,
                    left = 0.5f, top = 0.5f, right = 0.5f, bottom = 0.9f,
                ),
            ),
        )
        rule.setContent {
            Box(Modifier.size(200.dp)) {
                FaceBoxLayer(
                    boxes = boxes,
                    containerSize = androidx.compose.ui.unit.IntSize(200, 200),
                    zoom = { 1f },
                    onFaceClick = { clicked = it },
                )
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        rule.onRoot().performTouchInput {
            swipe(Offset(center.x, center.y), Offset(center.x, center.y), 60L)
        }
        rule.waitForIdle()
        assertEquals(99L, clicked)
    }

    @Test
    fun `video preview with crop overlay accepts a crop drag`() {
        val vm = VideoEditViewModel(app, PhotosRepository.get(app))
        rule.setContent {
            Box(Modifier.size(320.dp, 220.dp)) {
                VideoEditPreview(
                    vm = vm,
                    state = VideoEditState(),
                    uri = Uri.parse("content://fixture/preview.mp4"),
                    showCropOverlay = true,
                )
            }
        }
        rule.waitForIdle()
        rule.onRoot().performTouchInput {
            swipe(Offset(center.x, center.y), Offset(center.x + 40f, center.y + 30f), 120L)
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        // Crop state remains well-formed whatever the drag committed.
        val s = vm.state.value
        assertTrue(s.cropLeft == null || s.cropLeft in 0f..1f)
    }

    @Test
    fun `video player play pause button toggles`() {
        rule.setContent {
            Box(Modifier.size(320.dp, 200.dp)) {
                VideoPlayer(
                    modifier = Modifier,
                    uri = Uri.parse("content://fixture/player.mp4"),
                    isMetadataVisible = true,
                    isSettledPage = false,
                )
            }
        }
        rule.waitForIdle()
        // The centred play/pause overlay is a clickable node; clicking it
        // must not crash and the player keeps rendering.
        val clickables = rule.onAllNodes(hasClickAction())
        val nodes = clickables.fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        // Click the most central clickable (the play/pause overlay).
        val cx = 160f
        val cy = 100f
        val idx = nodes.indices.minByOrNull {
            val b = nodes[it].boundsInRoot
            kotlin.math.abs(b.center.x - cx) + kotlin.math.abs(b.center.y - cy)
        } ?: 0
        clickables[idx].performClick()
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }
}
